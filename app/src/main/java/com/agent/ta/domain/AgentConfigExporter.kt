package com.agent.ta.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
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

                // Phase 2 关系系统：写入关系快照 relationship.json
                try {
                    val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
                    val relationshipState = ServiceLocator.relationshipStateDao.get(agentId)
                    val recentMilestones = ServiceLocator.milestoneEventDao.getRecent(agentId, 50)
                    val relationshipSnapshot = buildJsonObject {
                        if (relationshipState != null) {
                            put("state", buildJsonObject {
                                put("currentStage", relationshipState.currentStage)
                                put("intimacyScore", relationshipState.intimacyScore)
                                put("trustScore", relationshipState.trustScore)
                                put("interactionCount", relationshipState.interactionCount)
                                put("lastInteractionAt", relationshipState.lastInteractionAt)
                            })
                        }
                        put("milestones", buildJsonArray {
                            recentMilestones.forEach { m ->
                                add(buildJsonObject {
                                    put("type", m.type)
                                    put("title", m.title)
                                    put("triggeredAt", m.triggeredAt)
                                    put("triggerSource", m.triggerSource)
                                })
                            }
                        })
                    }.toString()
                    zos.putNextEntry(ZipEntry("relationship.json"))
                    zos.write(relationshipSnapshot.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                } catch (e: Exception) {
                    Log.w(TAG, "导出关系快照失败（不影响主流程）", e)
                }

                // 写入 memory.json — 习得记忆 top 50（排除 source="seed" 的初始记忆）
                try {
                    val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
                    val allMemories = ServiceLocator.memoryDao.getByMinImportance(agentId, 0)
                    val topMemories = allMemories
                        .filter { it.source != "seed" }
                        .sortedWith(
                            compareByDescending<com.agent.ta.data.local.entity.MemoryEntity> { it.importance }
                                .thenByDescending { it.createdAt }
                        )
                        .take(50)
                    val memorySnapshot = buildJsonObject {
                        put("topMemories", buildJsonArray {
                            topMemories.forEach { m ->
                                add(buildJsonObject {
                                    put("type", m.type)
                                    put("category", m.category)
                                    put("content", m.content)
                                    put("importance", m.importance)
                                })
                            }
                        })
                        put("exportedAt", System.currentTimeMillis())
                    }.toString()
                    zos.putNextEntry(ZipEntry("memory.json"))
                    zos.write(memorySnapshot.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                } catch (e: Exception) {
                    Log.w(TAG, "导出习得记忆失败（不影响主流程）", e)
                }

                // 写入 recent_chats.json — 近期对话 top 100（仅导出 text 非空的消息，不含 audio_path）
                try {
                    val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
                    val allMessages = ServiceLocator.chatMessageDao.getAll(agentId)
                    val recentChats = allMessages
                        .filter { !it.text.isNullOrBlank() }
                        .takeLast(100)
                    val recentChatsSnapshot = buildJsonObject {
                        put("recentChats", buildJsonArray {
                            recentChats.forEach { msg ->
                                add(buildJsonObject {
                                    put("direction", msg.direction)
                                    put("text", msg.text)
                                    put("createdAt", msg.createdAt)
                                })
                            }
                        })
                        put("exportedAt", System.currentTimeMillis())
                    }.toString()
                    zos.putNextEntry(ZipEntry("recent_chats.json"))
                    zos.write(recentChatsSnapshot.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                } catch (e: Exception) {
                    Log.w(TAG, "导出近期对话失败（不影响主流程）", e)
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
