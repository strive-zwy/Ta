package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agent.ta.data.local.entity.EmotionalStateEntity

@Dao
interface EmotionalStateDao {

    /**
     * 查询指定 Agent 的情绪状态
     */
    @Query("SELECT * FROM emotional_state WHERE agentId = :agentId LIMIT 1")
    suspend fun get(agentId: Long): EmotionalStateEntity?

    /**
     * 插入或替换（首次初始化用）
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: EmotionalStateEntity)

    /**
     * 更新完整情绪状态（valence/arousal/势能/lastEmotion/静默计时/衰减计时）
     */
    @Query("UPDATE emotional_state SET valence = :valence, arousal = :arousal, potentialEnergy = :potentialEnergy, lastEmotion = :lastEmotion, lastUserInteractionAt = :lastUserInteractionAt, lastDecayAt = :lastDecayAt, updatedAt = :updatedAt WHERE agentId = :agentId")
    suspend fun updateState(
        agentId: Long,
        valence: Float,
        arousal: Float,
        potentialEnergy: Int,
        lastEmotion: String?,
        lastUserInteractionAt: Long,
        lastDecayAt: Long,
        updatedAt: Long = System.currentTimeMillis()
    )

    /**
     * 仅更新势能（用于 BoredInitiator 消耗势能）
     */
    @Query("UPDATE emotional_state SET potentialEnergy = :energy, updatedAt = :updatedAt WHERE agentId = :agentId")
    suspend fun updateEnergy(agentId: Long, energy: Int, updatedAt: Long = System.currentTimeMillis())

    /**
     * 仅更新用户互动时间戳（用于 ChatInteractor 用户发消息时重置静默计时）
     */
    @Query("UPDATE emotional_state SET lastUserInteractionAt = :ts, updatedAt = :updatedAt WHERE agentId = :agentId")
    suspend fun updateLastUserInteraction(agentId: Long, ts: Long, updatedAt: Long = System.currentTimeMillis())

    /**
     * 仅更新衰减时间戳（用于每小时衰减完成后记录）
     */
    @Query("UPDATE emotional_state SET lastDecayAt = :ts, updatedAt = :updatedAt WHERE agentId = :agentId")
    suspend fun updateDecayTime(agentId: Long, ts: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM emotional_state WHERE agentId = :agentId")
    suspend fun deleteByAgentId(agentId: Long)
}
