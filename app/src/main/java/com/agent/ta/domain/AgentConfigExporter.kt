package com.agent.ta.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Agent 配置导出器
 *
 * 将当前 AgentConfig 导出为 .agent.zip，与 AgentConfigImporter 兼容：
 * - agent.json：AgentConfig 序列化（路径改回相对路径）
 * - avatars/：头像文件（可选）
 * - voice/：语音样本文件（可选）
 *
 * 注意：配置包不含任何 API Key / Base URL / Model，纯人格配置
 */
class AgentConfigExporter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * 导出当前 AgentConfig 为 .agent.zip
     *
     * @param context 用于访问 ContentResolver
     * @param outputUri SAF Uri，用户选择的保存位置
     * @return 导出的 Agent 名称
     */
    suspend fun export(context: Context, outputUri: Uri): String = withContext(Dispatchers.IO) {
        val config = ServiceLocator.agentConfigProvider.get()

        // 1. 把绝对路径改回相对路径（用于 agent.json）
        val exportConfig = rewritePathsToRelative(config)
        val agentJson = json.encodeToString(AgentConfig.serializer(), exportConfig)

        // 2. 创建 zip 并写入
        val writtenFiles = mutableSetOf<String>()
        context.contentResolver.openOutputStream(outputUri)?.use { os ->
            ZipOutputStream(os).use { zos ->
                // 写入 agent.json
                zos.putNextEntry(ZipEntry("agent.json"))
                zos.write(agentJson.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 写入头像文件（去重）
                config.agent.avatars.forEach { avatar ->
                    val file = File(avatar.file)
                    if (file.exists() && file.isAbsolute) {
                        val relativePath = "avatars/${file.name}"
                        if (writtenFiles.add(relativePath)) {
                            zos.putNextEntry(ZipEntry(relativePath))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }

                // 写入语音样本（v1 单样本 + v3 情绪样本，去重）
                config.voice.allSamplePaths().forEach { path ->
                    val file = File(path)
                    if (file.exists() && file.isAbsolute) {
                        val relativePath = "voice/${file.name}"
                        if (writtenFiles.add(relativePath)) {
                            zos.putNextEntry(ZipEntry(relativePath))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
        } ?: throw IllegalStateException("无法打开输出流")

        Log.d(TAG, "导出完成：${config.agent.name}")
        config.agent.name
    }

    /**
     * 把绝对路径改回相对路径（avatars/xxx.jpg / voice/sample.wav）
     * 这样导出的 .agent.zip 可以被 AgentConfigImporter 正确导入
     */
    private fun rewritePathsToRelative(config: AgentConfig): AgentConfig {
        val newAvatars = config.agent.avatars.map { avatar ->
            val file = File(avatar.file)
            val relativePath = if (file.isAbsolute && file.exists()) {
                "avatars/${file.name}"
            } else {
                avatar.file
            }
            avatar.copy(file = relativePath)
        }

        // 改写 v1 sampleFile + emotions 里每个情绪的 sampleFile
        val newSampleFile = config.voice.sampleFile.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (file.isAbsolute && file.exists()) "voice/${file.name}" else path
        } ?: config.voice.sampleFile

        val newEmotions = config.voice.emotions.mapValues { (_, emotionCfg) ->
            val newPath = emotionCfg.sampleFile.takeIf { it.isNotBlank() }?.let { path ->
                val file = File(path)
                if (file.isAbsolute && file.exists()) "voice/${file.name}" else path
            } ?: emotionCfg.sampleFile
            emotionCfg.copy(sampleFile = newPath)
        }

        return config.copy(
            agent = config.agent.copy(avatars = newAvatars),
            voice = config.voice.copy(
                sampleFile = newSampleFile,
                emotions = newEmotions
            )
        )
    }

    companion object {
        private const val TAG = "AgentConfigExporter"
    }
}
