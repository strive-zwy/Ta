package com.agent.ta.domain

import com.agent.ta.data.local.entity.RelationshipStateEntity
import com.agent.ta.di.ServiceLocator

/**
 * 关系状态初始化器
 *
 * 首次启动时为指定 Agent 插入初始记录（stage="stranger", scores=0）
 */
class RelationshipInitializer {

    /**
     * 确保指定 Agent 的关系状态已初始化，若为 null 则插入初始记录
     */
    suspend fun ensureInitialized(agentId: Long): RelationshipStateEntity {
        val existing = ServiceLocator.relationshipStateDao.get(agentId)
        if (existing != null) return existing

        val now = System.currentTimeMillis()
        val initial = RelationshipStateEntity(
            agentId = agentId,
            currentStage = "stranger",
            intimacyScore = 0,
            trustScore = 0,
            interactionCount = 0,
            lastInteractionAt = now,
            lastDecayAt = now,
            createdAt = now,
            updatedAt = now
        )
        ServiceLocator.relationshipStateDao.upsert(initial)
        return initial
    }
}
