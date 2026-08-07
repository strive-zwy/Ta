package com.agent.ta.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.agent.ta.data.local.entity.AgentConfigEntity
import com.agent.ta.data.local.entity.EmotionalStateEntity
import com.agent.ta.data.local.entity.FirstMeetingStateEntity
import com.agent.ta.data.local.entity.RelationshipStateEntity
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.di.ServiceLocator
import com.agent.ta.util.AgentConfigImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Agent 导入流程编排
 *
 * 单 Agent 模式：app 里始终只有一个 Agent。
 *
 * 导入规则：
 * - 名称与当前 Agent 相同 → 原地更新配置，保留聊天记录和记忆（相当于更新配置）
 * - 名称不同 → 替换为全新 Agent，清空所有聊天记录和记忆，重新开始
 *
 * 两种情况都不继承导出者的用户称呼（nicknameForUser）。
 */
class AgentImportManager(private val context: Context) {

    private val importer = AgentConfigImporter(context)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * 导入 .agent.zip
     *
     * @return 导入成功的 Agent 名称
     * @throws IllegalArgumentException 校验失败
     */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val rawConfig = importer.importFromUri(uri)

        // 清空导出者的用户称呼，不继承到新实例
        val importConfig = rawConfig.copy(
            agent = rawConfig.agent.copy(
                persona = rawConfig.agent.persona.copy(nicknameForUser = "")
            )
        )
        val configJson = json.encodeToString(AgentConfig.serializer(), importConfig)
        val newAgentName = importConfig.agent.name
        val now = System.currentTimeMillis()

        importMutex.withLock {
            val currentActive = ServiceLocator.agentConfigDao.getActive()
            val isSameName = currentActive != null && currentActive.agentName == newAgentName
            val oldCommitments = currentActive?.let {
                ServiceLocator.commitmentDao.getAll(it.id)
            }.orEmpty()

            AgentGenerationRegistry.shared.advance()
            ChatInteractor.cancelAndJoinForAgentSwitch()
            oldCommitments.forEach {
                com.agent.ta.service.CommitmentScheduler(context)
                    .cancelCommitmentTrigger(it.agentId, it.id)
            }

            val agentId: Long = if (isSameName) {
                // === 名称相同：原地更新配置，保留所有数据 ===
                updateConfigInPlace(currentActive!!.id, configJson, newAgentName)
                Log.d(TAG, "名称相同，原地更新配置：${newAgentName}（id=${currentActive.id}），保留聊天记录和记忆")
                currentActive.id
            } else {
                // === 名称不同：替换为全新 Agent，清空所有数据 ===
                val newId = replaceAgentWithFreshData(configJson, newAgentName, now)
                Log.d(TAG, "名称不同，替换为全新 Agent：${newAgentName}（id=$newId），清空聊天记录和记忆")
                newId
            }

            ServiceLocator.activeAgentManager.switchTo(agentId)

            injectMemorySeeds(agentId, importConfig)

            ServiceLocator.agentConfigProvider.reload()

            com.agent.ta.service.AgentEngine.reloadAfterConfigChanged(context, agentId)

            if (isSameName) {
                ServiceLocator.chatMessageDao.releaseProcessing(agentId)
                ChatInteractor(context).processPendingReplies()
            } else {
                // 仅全新 Agent 触发首次见面问候；更新配置的不重新问候
                ChatInteractor(context).triggerFirstMeetingGreeting()
            }

            newAgentName
        }
    }

    /**
     * 名称相同：原地更新配置，保留聊天记录、记忆、关系、情绪等所有数据
     */
    private suspend fun updateConfigInPlace(
        agentId: Long,
        configJson: String,
        agentName: String
    ) {
        ServiceLocator.database.withTransaction {
            ServiceLocator.agentConfigDao.updateById(agentId, configJson, agentName)
        }
    }

    /**
     * 名称不同：清空旧 Agent 所有数据，删除旧配置，插入新配置并初始化状态
     *
     * @return 新 Agent 的 ID
     */
    private suspend fun replaceAgentWithFreshData(
        configJson: String,
        agentName: String,
        now: Long
    ): Long {
        return ServiceLocator.database.withTransaction {
        val agentConfigDao = ServiceLocator.agentConfigDao

        // 清空旧 Agent 的所有数据并删除配置记录（如果有）
        val oldActive = agentConfigDao.getActive()
        if (oldActive != null) {
            deleteAllAgentData(oldActive.id)
            agentConfigDao.deleteById(oldActive.id)
        }

        // 插入新配置并激活
        val newId = agentConfigDao.insert(
            AgentConfigEntity(
                configJson = configJson,
                agentName = agentName,
                importedAt = now,
                isActive = true
            )
        )

        // 初始化首次见面状态
        ServiceLocator.firstMeetingStateDao.upsert(
            FirstMeetingStateEntity(
                agentId = newId,
                phase = "NOT_STARTED"
            )
        )
        // 初始化关系状态
        ServiceLocator.relationshipStateDao.upsert(
            RelationshipStateEntity(
                agentId = newId,
                currentStage = "stranger",
                intimacyScore = 0,
                trustScore = 0,
                interactionCount = 0,
                lastInteractionAt = now,
                lastDecayAt = now
            )
        )
        // 初始化情绪状态
        ServiceLocator.emotionalStateDao.upsert(
            EmotionalStateEntity(
                agentId = newId,
                valence = 0f,
                arousal = 0.3f,
                potentialEnergy = 0,
                lastEmotion = null,
                lastUserInteractionAt = now,
                lastDecayAt = now
            )
        )
        newId
        }
    }

    /**
     * 删除指定 Agent 的所有关联数据（聊天、记忆、作息、状态、承诺等）
     */
    private suspend fun deleteAllAgentData(agentId: Long) {
        ServiceLocator.chatMessageDao.deleteAll(agentId)
        ServiceLocator.memoryDao.deleteAll(agentId)
        ServiceLocator.firstMeetingStateDao.deleteByAgentId(agentId)
        ServiceLocator.relationshipStateDao.deleteByAgentId(agentId)
        ServiceLocator.emotionalStateDao.deleteByAgentId(agentId)
        ServiceLocator.dailyScheduleDao.deleteAll(agentId)
        ServiceLocator.dailyStateDao.deleteAll(agentId)
        ServiceLocator.futureEventDao.deleteAll(agentId)
        ServiceLocator.stateLogDao.deleteAll(agentId)
        ServiceLocator.conversationSummaryDao.deleteAll(agentId)
        ServiceLocator.commitmentDao.deleteAll(agentId)
        ServiceLocator.milestoneEventDao.deleteAll(agentId)
    }

    /**
     * 把 persona.memorySeeds 注入到记忆库
     *
     * 这些是 Agent 与用户之间的"初始共享记忆"（如"我们在大学认识"），
     * 在导入时一次性写入，类型为 user_profile，来源标记为 seed
     * 后续 LLM 在对话中产生的记忆更新走 source=chat，区分开
     */
    private suspend fun injectMemorySeeds(agentId: Long, config: AgentConfig) {
        val seeds = config.agent.persona.memorySeeds
        if (seeds.isEmpty()) return

        val memoryDao = ServiceLocator.memoryDao
        val now = System.currentTimeMillis()
        seeds.forEach { seed ->
            val content = seed.trim()
            if (content.isBlank()) return@forEach
            // 去重：避免重复导入同一 .agent.zip 导致记忆库出现重复 seed
            if (memoryDao.countSeedByContent(agentId, content) > 0) return@forEach
            memoryDao.insert(
                com.agent.ta.data.local.entity.MemoryEntity(
                    agentId = agentId,
                    type = "shared",
                    category = "初始记忆",
                    content = content,
                    importance = 4,  // 初始记忆重要性较高
                    source = "seed",
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        Log.d(TAG, "已注入 ${seeds.size} 条初始记忆（memory_seeds）")
    }

    companion object {
        private val importMutex = Mutex()
    }
}

private const val TAG = "AgentImportManager"
