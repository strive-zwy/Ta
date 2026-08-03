package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agent.ta.data.local.entity.RelationshipStateEntity

@Dao
interface RelationshipStateDao {

    /**
     * 查询单条记录（id 固定为 1）
     */
    @Query("SELECT * FROM relationship_state WHERE id = 1 LIMIT 1")
    suspend fun get(): RelationshipStateEntity?

    /**
     * 插入或替换（首次初始化用）
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: RelationshipStateEntity)

    /**
     * 更新分数和互动计数
     */
    @Query("UPDATE relationship_state SET intimacyScore = :intimacy, trustScore = :trust, interactionCount = :interactionCount, lastInteractionAt = :lastInteractionAt, updatedAt = :updatedAt WHERE id = 1")
    suspend fun updateScores(intimacy: Int, trust: Int, interactionCount: Int, lastInteractionAt: Long, updatedAt: Long = System.currentTimeMillis())

    /**
     * 更新阶段
     */
    @Query("UPDATE relationship_state SET currentStage = :stage, updatedAt = :updatedAt WHERE id = 1")
    suspend fun updateStage(stage: String, updatedAt: Long = System.currentTimeMillis())

    /**
     * 更新衰减时间戳
     */
    @Query("UPDATE relationship_state SET lastDecayAt = :lastDecayAt, updatedAt = :updatedAt WHERE id = 1")
    suspend fun updateDecayTime(lastDecayAt: Long, updatedAt: Long = System.currentTimeMillis())
}
