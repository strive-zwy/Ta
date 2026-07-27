package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agent.ta.data.local.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MemoryEntity): Long

    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE type = :type ORDER BY importance DESC, updatedAt DESC")
    suspend fun getByType(type: String): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun getTopMemories(limit: Int = 20): List<MemoryEntity>

    /**
     * 按关键词搜索记忆（LIKE 模糊匹配 content）
     * 用于 MemoryTool 让 LLM 主动检索历史记忆
     */
    @Query("SELECT * FROM memories WHERE content LIKE '%' || :keyword || '%' ORDER BY importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun searchByKeyword(keyword: String, limit: Int = 10): List<MemoryEntity>

    @Query("UPDATE memories SET content = :content, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, updatedAt: Long)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()

    @Query("DELETE FROM memories WHERE importance <= :threshold")
    suspend fun deleteLowImportance(threshold: Int = 2)

    @Query("UPDATE memories SET accessCount = accessCount + 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun incrementAccessCount(id: Long, updatedAt: Long)

    @Query("UPDATE memories SET importance = :importance, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateImportance(id: Long, importance: Int, updatedAt: Long)
}
