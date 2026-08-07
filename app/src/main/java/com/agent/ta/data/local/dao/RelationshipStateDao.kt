package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agent.ta.data.local.entity.RelationshipStateEntity

@Dao
interface RelationshipStateDao {

    /**
     * 查询指定 Agent 的关系状态
     */
    @Query("SELECT * FROM relationship_state WHERE agentId = :agentId LIMIT 1")
    suspend fun get(agentId: Long): RelationshipStateEntity?

    /**
     * 插入或替换（首次初始化用）
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: RelationshipStateEntity)

    /**
     * 更新分数和互动计数
     */
    @Query("UPDATE relationship_state SET intimacyScore = :intimacy, trustScore = :trust, interactionCount = :interactionCount, lastInteractionAt = :lastInteractionAt, updatedAt = :updatedAt WHERE agentId = :agentId")
    suspend fun updateScores(agentId: Long, intimacy: Int, trust: Int, interactionCount: Int, lastInteractionAt: Long, updatedAt: Long = System.currentTimeMillis())

    /**
     * 更新阶段
     */
    @Query("UPDATE relationship_state SET currentStage = :stage, updatedAt = :updatedAt WHERE agentId = :agentId")
    suspend fun updateStage(agentId: Long, stage: String, updatedAt: Long = System.currentTimeMillis())

    /**
     * 更新衰减时间戳
     */
    @Query("UPDATE relationship_state SET lastDecayAt = :lastDecayAt, updatedAt = :updatedAt WHERE agentId = :agentId")
    suspend fun updateDecayTime(agentId: Long, lastDecayAt: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM relationship_state WHERE agentId = :agentId")
    suspend fun deleteByAgentId(agentId: Long)
}
