package com.agent.ta.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.agent.ta.data.model.AgentConfig
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * .agent.zip 导入器
 *
 * 规范（见 PROJECT_PLAN.md 第五节）：
 * - 必须包含 agent.json
 * - 可选包含 avatars 目录内容与 voice/sample.wav
 *
 * 本导入器职责：
 * 1. 解压 zip 到内部存储 files/agents/imported/<id>/
 * 2. 解析 agent.json 为 AgentConfig
 * 3. 把 avatars/ 与 voice/ 的相对路径改写为本地绝对路径，供运行时直接使用
 * 4. 校验必需字段（agent.name / persona.background / directorRoleTemplate / systemPromptTemplate
 *    / voice.sampleFile 存在）
 *
 * 注意：配置包内不含任何 API Key / Base URL / Model，与运行时模型完全解耦
 */
class AgentConfigImporter(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * 从 SAF Uri 导入 .agent.zip
     *
     * @param uri 用户通过 SAF 选择的文件 Uri
     * @return 导入成功后的 AgentConfig（路径已改写为本地绝对路径）
     * @throws IllegalArgumentException 校验失败时抛出，message 为具体原因
     */
    suspend fun importFromUri(uri: Uri): AgentConfig {
        val importDir = File(context.filesDir, "agents/imported/${System.currentTimeMillis()}")
        importDir.mkdirs()

        // 1. 解压 zip
        val extractedFiles = copyUriToZipAndExtract(uri, importDir)

        // 2. 读取并解析 agent.json
        val agentJsonFile = File(importDir, "agent.json")
        if (!agentJsonFile.exists()) {
            throw IllegalArgumentException("配置包缺少 agent.json")
        }
        val config = try {
            json.decodeFromString<AgentConfig>(agentJsonFile.readText())
        } catch (e: Exception) {
            throw IllegalArgumentException("agent.json 解析失败：${e.message}")
        }

        // 3. 校验必需字段
        validate(config)

        // 4. 改写头像/音频路径为本地绝对路径
        val rewritten = rewritePaths(config, importDir, extractedFiles)

        // 5. 保留 agent.json 副本（用于后续入库 configJson）
        Log.d(TAG, "导入完成：${rewritten.agent.name}，文件目录 ${importDir.absolutePath}")
        return rewritten
    }

    /**
     * 把 SAF Uri 的内容写入临时 zip，再解压到目标目录
     * 返回解压得到的相对路径集合（用于校验和改写）
     */
    private fun copyUriToZipAndExtract(uri: Uri, targetDir: File): Set<String> {
        val extracted = mutableSetOf<String>()
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) {
                throw IllegalArgumentException("无法读取所选文件")
            }
            ZipInputStream(input).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    // 防止 Zip Slip 攻击：确保解压路径在 targetDir 内
                    val outFile = File(targetDir, name)
                    val canonicalTarget = outFile.canonicalPath
                    val canonicalBase = targetDir.canonicalPath
                    if (!canonicalTarget.startsWith(canonicalBase + File.separator) &&
                        canonicalTarget != canonicalBase
                    ) {
                        Log.w(TAG, "跳过可疑路径：$name")
                        entry = zis.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        extracted.add(name)
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return extracted
    }

    /**
     * 校验必需字段
     *
     * Phase 4 放宽规则（支持克隆生成的 identity 驱动配置）：
     * - agent.name 必须非空
     * - identity.worldSetting 或 persona.background 至少一个非空
     *   （identity 驱动模式不强制 persona.background，反之亦然）
     * - directorRoleTemplate 不再强制（styleEnabled=false 时让 TTS 自主分析）
     *
     * 注：systemPromptTemplate 当前由 PromptBuilder 内部硬编码生成，故不强制要求
     */
    private fun validate(config: AgentConfig) {
        require(config.agent.name.isNotBlank()) { "agent.name 不能为空" }
        val hasIdentity = config.identity.worldSetting.isNotBlank()
        val hasPersonaBackground = config.agent.persona.background.isNotBlank()
        require(hasIdentity || hasPersonaBackground) {
            "identity.world_setting 和 persona.background 至少一个不能为空"
        }
    }

    /**
     * 把配置内的相对路径（avatars/xxx.jpg / voice/sample.wav）
     * 改写为本地绝对路径，供运行时直接读取
     */
    private fun rewritePaths(
        config: AgentConfig,
        baseDir: File,
        extractedFiles: Set<String>
    ): AgentConfig {
        // 头像
        val newAvatars = config.agent.avatars.map { avatar ->
            val localFile = resolveAndCheck(baseDir, avatar.file, extractedFiles)
            avatar.copy(file = localFile?.absolutePath ?: avatar.file)
        }

        // 音频样本：改写 v1 sampleFile + emotions 里每个情绪的 sampleFile
        // - v1 sampleFile 允许为空（运行时降级系统 TTS）
        // - emotions 里每个情绪的 sampleFile 允许为空（fallback 到 neutral）
        val newVoice = config.voice.let { v ->
            // 改写 v1 sampleFile
            val newSampleFile = if (v.sampleFile.isBlank()) {
                ""
            } else {
                val localFile = resolveAndCheck(baseDir, v.sampleFile, extractedFiles)
                if (localFile == null) {
                    // v1 sampleFile 不存在时，尝试用 emotions[neutral] 的样本兜底
                    val neutralSample = v.emotions[com.agent.ta.data.model.VoiceEmotionConfig.NEUTRAL]?.sampleFile
                    val neutralLocal = neutralSample?.let { resolveAndCheck(baseDir, it, extractedFiles) }
                    if (neutralLocal != null) {
                        Log.w(TAG, "voice.sample_file 不存在，用 emotions[neutral] 兜底：${neutralLocal.absolutePath}")
                        neutralLocal.absolutePath
                    } else {
                        // 都没有，允许通过（neutral 样本可在 Admin UI 后续配置）
                        Log.w(TAG, "voice.sample_file 不存在且无 neutral 样本兜底：${v.sampleFile}")
                        ""
                    }
                } else {
                    localFile.absolutePath
                }
            }

            // 改写 emotions 里每个情绪的 sampleFile
            val newEmotions = v.emotions.mapValues { (emotion, emotionCfg) ->
                val newPath = emotionCfg.sampleFile.takeIf { it.isNotBlank() }?.let { path ->
                    resolveAndCheck(baseDir, path, extractedFiles)?.absolutePath ?: path
                } ?: emotionCfg.sampleFile
                emotionCfg.copy(sampleFile = newPath)
            }

            // v1/v3 同步：如果 neutral 的 v3 sampleFile 为空但 v1 sampleFile 有值，
            // 把 v1 路径同步到 neutral，确保 UI 和 TTS 读取一致
            // （旧格式配置包可能只在 voice.sample_file 存了路径，emotions 里为空）
            val syncedEmotions = if (newSampleFile.isNotBlank()) {
                val neutralCfg = newEmotions[com.agent.ta.data.model.VoiceEmotionConfig.NEUTRAL]
                if (neutralCfg != null && neutralCfg.sampleFile.isBlank()) {
                    newEmotions.toMutableMap().apply {
                        put(
                            com.agent.ta.data.model.VoiceEmotionConfig.NEUTRAL,
                            neutralCfg.copy(sampleFile = newSampleFile)
                        )
                    }
                } else {
                    newEmotions
                }
            } else {
                newEmotions
            }

            v.copy(
                sampleFile = newSampleFile,
                emotions = syncedEmotions
            )
        }

        return config.copy(
            agent = config.agent.copy(avatars = newAvatars),
            voice = newVoice
        )
    }

    /**
     * 解析相对路径并校验文件确实存在
     */
    private fun resolveAndCheck(
        baseDir: File,
        relativePath: String,
        extractedFiles: Set<String>
    ): File? {
        if (relativePath.isBlank()) return null
        val file = File(baseDir, relativePath)
        // 校验解压阶段确实拿到了这个文件
        if (!extractedFiles.contains(relativePath) && !file.exists()) {
            Log.w(TAG, "配置包中引用的文件未找到：$relativePath")
            return null
        }
        return file
    }

    companion object {
        private const val TAG = "AgentConfigImporter"
    }
}
