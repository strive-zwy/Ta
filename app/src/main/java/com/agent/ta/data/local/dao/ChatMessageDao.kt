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

    @Query("SELECT * FROM chat_messages WHERE status != 'hidden' ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE status != 'hidden' ORDER BY createdAt ASC")
    suspend fun getAll(): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE status = 'pending' ORDER BY createdAt ASC")
    suspend fun getPendingMessages(): List<ChatMessageEntity>

    /**
     * 查询最近一条用户消息（inbound），避免全表扫描
     * 用于 RecentConversationObserver 心跳检查
     */
    @Query("SELECT * FROM chat_messages WHERE direction = 'inbound' ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLastInboundMessage(): ChatMessageEntity?

    @Query("UPDATE chat_messages SET status = :status, repliedAt = :repliedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, repliedAt: Long?)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM chat_messages WHERE direction = 'outbound' AND status = 'sent' AND createdAt > :since")
    suspend fun countOutboundSince(since: Long): Int
}
