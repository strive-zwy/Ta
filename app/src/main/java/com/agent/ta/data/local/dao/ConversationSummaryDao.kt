package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agent.ta.data.local.entity.ConversationSummaryEntity

/**
 * 对话摘要 DAO（v2 L2 认知层）
 */
@Dao
interface ConversationSummaryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ConversationSummaryEntity): Long

    /**
     * 获取指定桶 ID 之前的所有摘要（按桶 ID 升序）
     * 用于注入 Prompt Zone B，让 LLM 知道之前聊过什么
     */
    @Query("SELECT * FROM conversation_summaries WHERE agentId = :agentId AND bucketId < :currentBucketId ORDER BY bucketId ASC")
    suspend fun getPriorSummaries(agentId: Long, currentBucketId: Long): List<ConversationSummaryEntity>

    /**
     * 获取所有摘要（按桶 ID 升序）
     */
    @Query("SELECT * FROM conversation_summaries WHERE agentId = :agentId ORDER BY bucketId ASC")
    suspend fun getAll(agentId: Long): List<ConversationSummaryEntity>

    /**
     * 获取指定桶 ID 的摘要
     */
    @Query("SELECT * FROM conversation_summaries WHERE agentId = :agentId AND bucketId = :bucketId LIMIT 1")
    suspend fun getByBucketId(agentId: Long, bucketId: Long): ConversationSummaryEntity?

    /**
     * 获取最大桶 ID（用于判断当前桶位置）
     */
    @Query("SELECT MAX(bucketId) FROM conversation_summaries WHERE agentId = :agentId")
    suspend fun getMaxBucketId(agentId: Long): Long?

    /**
     * 删除指定 Agent 的所有摘要
     */
    @Query("DELETE FROM conversation_summaries WHERE agentId = :agentId")
    suspend fun deleteAll(agentId: Long)
}
