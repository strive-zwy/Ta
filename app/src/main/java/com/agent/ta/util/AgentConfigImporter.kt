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
     * 注：systemPromptTemplate 当前由 PromptBuilder 内部硬编码生成，故不强制要求
     */
    private fun validate(config: AgentConfig) {
        require(config.agent.name.isNotBlank()) { "agent.name 不能为空" }
        require(config.agent.persona.background.isNotBlank()) {
            "agent.persona.background 不能为空"
        }
        require(config.agent.persona.directorRoleTemplate.isNotBlank()) {
            "agent.persona.director_role_template 不能为空"
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

        // 音频样本：
        // - v1 单样本：sampleFile 为空时允许通过（默认无样本，运行时降级系统 TTS）
        // - v2 多样本：sampleFiles 列表，校验每条文件是否存在，并把路径改写为本地绝对路径
        val newVoice = config.voice.let { v ->
            // 改写多样本列表
            val newSampleFiles = v.sampleFiles.mapNotNull { sf ->
                val localFile = resolveAndCheck(baseDir, sf.file, extractedFiles)
                if (localFile == null) {
                    Log.w(TAG, "voice.sample_files 中文件未找到：${sf.file}，跳过该样本")
                    null
                } else {
                    sf.copy(file = localFile.absolutePath)
                }
            }
            // 改写 v1 兼容字段 sampleFile
            val newSampleFile = if (v.sampleFile.isBlank()) {
                ""
            } else {
                val localFile = resolveAndCheck(baseDir, v.sampleFile, extractedFiles)
                if (localFile == null) {
                    // v2 兼容：旧 sampleFile 找不到时，尝试用 sampleFiles 中的主样本兜底
                    val primary = newSampleFiles.firstOrNull { it.primary }
                    if (primary != null) {
                        Log.w(TAG, "voice.sample_file 不存在，用 sample_files 中的 primary 兜底：${primary.file}")
                        primary.file
                    } else {
                        throw IllegalArgumentException("voice.sample_file 在配置包中不存在：${v.sampleFile}")
                    }
                } else {
                    localFile.absolutePath
                }
            }
            v.copy(
                sampleFile = newSampleFile,
                sampleFiles = newSampleFiles
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
