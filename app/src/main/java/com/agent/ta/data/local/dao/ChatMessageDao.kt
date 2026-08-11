package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agent.ta.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages WHERE agentId = :agentId AND status != 'hidden' ORDER BY createdAt ASC")
    fun observeAll(agentId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE agentId = :agentId AND status != 'hidden' ORDER BY createdAt ASC")
    suspend fun getAll(agentId: Long): List<ChatMessageEntity>

    /**
     * 分页：获取最近的 N 条消息（DESC，最新在前）
     */
    @Query("SELECT * FROM chat_messages WHERE agentId = :agentId AND status != 'hidden' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentMessagesDesc(agentId: Long, limit: Int): List<ChatMessageEntity>

    /**
     * 分页：获取指定时间戳之前的 N 条消息（DESC，最新在前）
     */
    @Query("SELECT * FROM chat_messages WHERE agentId = :agentId AND status != 'hidden' AND createdAt < :beforeCreatedAt ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getMessagesBeforeDesc(agentId: Long, beforeCreatedAt: Long, limit: Int): List<ChatMessageEntity>

    /**
     * 观察指定时间戳之后的新消息（ASC，用于实时追加新消息）
     */
    @Query("SELECT * FROM chat_messages WHERE agentId = :agentId AND status != 'hidden' AND createdAt > :afterCreatedAt ORDER BY createdAt ASC")
    fun observeMessagesAfter(agentId: Long, afterCreatedAt: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE agentId = :agentId AND status = 'pending' ORDER BY createdAt ASC")
    suspend fun getPendingMessages(agentId: Long): List<ChatMessageEntity>

    /**
     * 查询最近一条用户消息（inbound），避免全表扫描
     * 用于 RecentConversationObserver 心跳检查
     */
    @Query("SELECT * FROM chat_messages WHERE agentId = :agentId AND direction = 'inbound' ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLastInboundMessage(agentId: Long): ChatMessageEntity?

    /**
     * 更新消息状态：必须同时匹配 agentId 和 id，防止跨 Agent 误更新
     */
    @Query("UPDATE chat_messages SET status = :status, repliedAt = :repliedAt WHERE agentId = :agentId AND id = :id")
    suspend fun updateStatus(agentId: Long, id: Long, status: String, repliedAt: Long?)

    @Query("UPDATE chat_messages SET status = 'processing', batchId = :batchId, claimedAt = :claimedAt WHERE agentId = :agentId AND status = 'pending'")
    suspend fun claimPending(agentId: Long, batchId: String, claimedAt: Long): Int

    @Query("SELECT * FROM chat_messages WHERE agentId = :agentId AND status = 'processing' AND batchId = :batchId ORDER BY createdAt ASC")
    suspend fun getProcessingBatch(agentId: Long, batchId: String): List<ChatMessageEntity>

    @Query("UPDATE chat_messages SET status = 'received', repliedAt = :repliedAt, batchId = NULL, claimedAt = NULL WHERE agentId = :agentId AND status = 'processing' AND batchId = :batchId")
    suspend fun completeBatch(agentId: Long, batchId: String, repliedAt: Long): Int

    @Query("UPDATE chat_messages SET status = 'pending', batchId = NULL, claimedAt = NULL WHERE agentId = :agentId AND status = 'processing' AND batchId = :batchId")
    suspend fun releaseBatch(agentId: Long, batchId: String): Int

    @Query("UPDATE chat_messages SET status = 'pending', batchId = NULL, claimedAt = NULL WHERE status = 'processing' AND claimedAt IS NOT NULL AND claimedAt < :before")
    suspend fun releaseStaleProcessing(before: Long): Int

    @Query("UPDATE chat_messages SET status = 'pending', batchId = NULL, claimedAt = NULL WHERE agentId = :agentId AND status = 'processing'")
    suspend fun releaseProcessing(agentId: Long): Int

    @Query("DELETE FROM chat_messages WHERE agentId = :agentId")
    suspend fun deleteAll(agentId: Long)

    @Query("SELECT COUNT(*) FROM chat_messages WHERE agentId = :agentId AND direction = 'outbound' AND status = 'sent' AND createdAt > :since")
    suspend fun countOutboundSince(agentId: Long, since: Long): Int
}
