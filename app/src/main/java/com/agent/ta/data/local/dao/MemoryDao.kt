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

    /**
     * 统计指定 content 的 seed 记录数（用于导入去重，避免重复注入相同 memory_seed）
     */
    @Query("SELECT COUNT(*) FROM memories WHERE source = 'seed' AND content = :content")
    suspend fun countSeedByContent(content: String): Int

    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE type = :type ORDER BY importance DESC, updatedAt DESC")
    suspend fun getByType(type: String): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun getTopMemories(limit: Int = 20): List<MemoryEntity>

    /**
     * 按重要度阈值查询记忆（v2 三层记忆系统）
     * - importance >= threshold: 核心记忆（永驻 prompt）
     * - importance 在中间区间: 记忆项（按需召回）
     */
    @Query("SELECT * FROM memories WHERE importance >= :threshold ORDER BY updatedAt DESC")
    suspend fun getByMinImportance(threshold: Int): List<MemoryEntity>

    /**
     * 按重要度区间查询记忆（v2 三层记忆系统）
     */
    @Query("SELECT * FROM memories WHERE importance >= :min AND importance < :max ORDER BY importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun getByImportanceRange(min: Int, max: Int, limit: Int): List<MemoryEntity>

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

    /**
     * 按 category 查询记忆（记忆与承诺系统）
     * 用于按分类（如 "commitment" / "preference" 等）批量加载记忆条目
     */
    @Query("SELECT * FROM memories WHERE category = :category ORDER BY importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun getByCategory(category: String, limit: Int = 50): List<MemoryEntity>

    /**
     * 按 category + 时间范围查询（createdAt 字段）
     * 用于检索某分类在指定时间段内的记忆，例如"最近一周的承诺记录"
     */
    @Query("SELECT * FROM memories WHERE category = :category AND createdAt >= :startTs AND createdAt <= :endTs ORDER BY createdAt DESC")
    suspend fun getByCategoryAndDateRange(category: String, startTs: Long, endTs: Long): List<MemoryEntity>

    /**
     * 按 category + 关键词查询单条记忆（content LIKE 模糊匹配）
     * 主要用于去重检查：判断某分类下是否已存在包含指定关键词的记忆
     */
    @Query("SELECT * FROM memories WHERE category = :category AND content LIKE '%' || :keyword || '%' LIMIT 1")
    suspend fun findOneByCategoryAndKeyword(category: String, keyword: String): MemoryEntity?

    /**
     * 按 category + 关键词计数（content LIKE 模糊匹配）
     * 用于判断某分类下包含指定关键词的记忆数量，辅助去重决策
     */
    @Query("SELECT COUNT(*) FROM memories WHERE category = :category AND content LIKE '%' || :keyword || '%'")
    suspend fun countByCategoryAndKeyword(category: String, keyword: String): Int

    /**
     * 按 category + 时间清理记忆（删除 createdAt 早于 beforeTs 的记录）
     * 用于历史承诺/记忆的过期清理，返回被删除的行数
     */
    @Query("DELETE FROM memories WHERE category = :category AND createdAt < :beforeTs")
    suspend fun deleteByCategoryBefore(category: String, beforeTs: Long): Int
}
