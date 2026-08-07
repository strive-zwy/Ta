package com.agent.ta.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.agent.ta.data.model.AgentConfig
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedInputStream
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
        try {
            val extractionResult = copyUriToZipAndExtract(uri, importDir)
            val agentJsonFile = File(importDir, "agent.json")
            if (!agentJsonFile.exists()) throw IllegalArgumentException("配置包缺少 agent.json")
            val config = try {
                json.decodeFromString<AgentConfig>(agentJsonFile.readText())
            } catch (e: Exception) {
                throw IllegalArgumentException("agent.json 解析失败")
            }
            validate(config)
            val rewritten = rewritePaths(config, importDir, extractionResult)
            Log.d(TAG, "导入完成：${rewritten.agent.name}")
            return rewritten
        } catch (e: Exception) {
            importDir.deleteRecursively()
            throw e
        }
    }

    /**
     * 把 SAF Uri 的内容写入临时 zip，再解压到目标目录
     * 返回解压得到的相对路径集合（用于校验和改写）
     */
    private fun copyUriToZipAndExtract(uri: Uri, targetDir: File): ExtractionResult {
        val extracted = mutableSetOf<String>()
        val pathRenames = mutableMapOf<String, String>()
        context.contentResolver.openInputStream(uri).use { rawInput ->
            val input = rawInput?.let(::BufferedInputStream)
            if (input == null) {
                throw IllegalArgumentException("无法读取所选文件")
            }
            var archiveBytes = 0L
            var totalExtractedBytes = 0L
            var entryCount = 0
            ZipInputStream(input).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    entryCount++
                    if (entryCount > AgentImportPolicy.MAX_ENTRY_COUNT) {
                        throw IllegalArgumentException("配置包条目数量超限")
                    }
                    if (!AgentImportPolicy.isAllowedPath(name)) {
                        throw IllegalArgumentException("配置包包含不支持的文件：$name")
                    }
                    // 防止 Zip Slip 攻击：确保解压路径在 targetDir 内
                    val outFile = File(targetDir, name)
                    if (!AgentImportPolicy.isContained(targetDir, outFile)) {
                        throw IllegalArgumentException("配置包路径越界")
                    }
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
                        throw IllegalArgumentException("配置包不允许目录条目：$name")
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var entryBytes = 0L
                            while (true) {
                                val count = zis.read(buffer)
                                if (count < 0) break
                                entryBytes += count
                                archiveBytes += count
                                totalExtractedBytes += count
                                if (entryBytes > AgentImportPolicy.MAX_ENTRY_BYTES ||
                                    totalExtractedBytes > AgentImportPolicy.MAX_TOTAL_EXTRACTED_BYTES ||
                                    archiveBytes > AgentImportPolicy.MAX_ARCHIVE_BYTES
                                ) {
                                    throw IllegalArgumentException("配置包大小超限")
                                }
                                fos.write(buffer, 0, count)
                            }
                        }
                        if (name != "agent.json" && name !in OPTIONAL_JSON_FILES) {
                            val bytes = outFile.inputStream().use { stream ->
                                val header = ByteArray(64)
                                val count = stream.read(header)
                                if (count <= 0) byteArrayOf() else header.copyOf(count)
                            }
                            // 头像和音频：根据真实文件头规范化路径
                            if (name.startsWith("avatars/")) {
                                val normalizedPath = AgentImportPolicy.normalizedAvatarPath(name, bytes)
                                if (normalizedPath == null) {
                                    throw IllegalArgumentException("资源文件格式无效：$name")
                                }
                                if (normalizedPath != name) {
                                    val renamedFile = ensureUniqueFile(targetDir, normalizedPath)
                                    outFile.renameTo(renamedFile)
                                    pathRenames[name] = normalizedPath
                                    extracted.add(normalizedPath)
                                } else {
                                    if (!AgentImportPolicy.hasSupportedSignature(name, bytes)) {
                                        throw IllegalArgumentException("资源文件格式无效：$name")
                                    }
                                    extracted.add(name)
                                }
                            } else if (name.startsWith("voice/")) {
                                val normalizedPath = AgentImportPolicy.normalizedVoicePath(name, bytes)
                                if (normalizedPath == null) {
                                    throw IllegalArgumentException("资源文件格式无效：$name")
                                }
                                if (normalizedPath != name) {
                                    val renamedFile = ensureUniqueFile(targetDir, normalizedPath)
                                    outFile.renameTo(renamedFile)
                                    pathRenames[name] = normalizedPath
                                    extracted.add(normalizedPath)
                                } else {
                                    if (!AgentImportPolicy.hasSupportedSignature(name, bytes)) {
                                        throw IllegalArgumentException("资源文件格式无效：$name")
                                    }
                                    extracted.add(name)
                                }
                            } else {
                                if (!AgentImportPolicy.hasSupportedSignature(name, bytes)) {
                                    throw IllegalArgumentException("资源文件格式无效：$name")
                                }
                                extracted.add(name)
                            }
                        } else {
                            extracted.add(name)
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return ExtractionResult(extracted, pathRenames)
    }

    /**
     * 确保目标文件名不冲突：若已存在则追加序号
     */
    private fun ensureUniqueFile(baseDir: File, relativePath: String): File {
        var candidate = File(baseDir, relativePath)
        if (!candidate.exists()) return candidate
        val dotIndex = relativePath.lastIndexOf('.')
        val base = if (dotIndex > 0) relativePath.substring(0, dotIndex) else relativePath
        val ext = if (dotIndex > 0) relativePath.substring(dotIndex) else ""
        var counter = 1
        do {
            candidate = File(baseDir, "$base _$counter$ext")
            counter++
        } while (candidate.exists())
        return candidate
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
        extractionResult: ExtractionResult
    ): AgentConfig {
        val extractedFiles = extractionResult.extractedFiles
        val renames = extractionResult.pathRenames
        // 头像：先应用路径映射，再解析为本地绝对路径
        val newAvatars = config.agent.avatars.map { avatar ->
            val mappedPath = renames[avatar.file] ?: avatar.file
            val localFile = resolveAndCheck(baseDir, mappedPath, extractedFiles)
            avatar.copy(file = localFile?.absolutePath ?: mappedPath)
        }

        // 音频样本：改写 v1 sampleFile + emotions 里每个情绪的 sampleFile
        // 先应用路径映射（旧扩展名→规范扩展名），再解析为本地绝对路径
        // - v1 sampleFile 允许为空（运行时降级系统 TTS）
        // - emotions 里每个情绪的 sampleFile 允许为空（fallback 到 neutral）
        val newVoice = config.voice.let { v ->
            // 改写 v1 sampleFile
            val mappedSampleFile = renames[v.sampleFile] ?: v.sampleFile
            val newSampleFile = if (mappedSampleFile.isBlank()) {
                ""
            } else {
                val localFile = resolveAndCheck(baseDir, mappedSampleFile, extractedFiles)
                if (localFile == null) {
                    // v1 sampleFile 不存在时，尝试用 emotions[neutral] 的样本兜底
                    val neutralSample = v.emotions[com.agent.ta.data.model.VoiceEmotionConfig.NEUTRAL]?.sampleFile
                    val mappedNeutral = neutralSample?.let { renames[it] ?: it }
                    val neutralLocal = mappedNeutral?.let { resolveAndCheck(baseDir, it, extractedFiles) }
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
                    val mapped = renames[path] ?: path
                    resolveAndCheck(baseDir, mapped, extractedFiles)?.absolutePath ?: mapped
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
        if (!AgentImportPolicy.isContained(baseDir, file)) return null
        // 校验解压阶段确实拿到了这个文件
        if (!extractedFiles.contains(relativePath) && !file.exists()) {
            Log.w(TAG, "配置包中引用的文件未找到：$relativePath")
            return null
        }
        if (!file.isFile) return null
        return file
    }

    companion object {
        private const val TAG = "AgentConfigImporter"
        private val OPTIONAL_JSON_FILES = setOf(
            "relationship.json",
            "memory.json",
            "recent_chats.json"
        )
    }

    /**
     * 解压结果：提取的文件路径集合 + 旧路径到规范路径的映射
     */
    data class ExtractionResult(
        val extractedFiles: Set<String>,
        val pathRenames: Map<String, String>
    )
}
