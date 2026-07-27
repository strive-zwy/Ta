package com.agent.ta.state.memory

import android.util.Log
import com.agent.ta.data.local.dao.MemoryDao
import com.agent.ta.data.local.entity.MemoryEntity
import com.agent.ta.data.remote.dto.MemoryUpdate

/**
 * 三层记忆系统（L1 状态层）
 *
 * 设计参考：MochiBot 的 Core Memory + Memory Items 分层
 *
 * 三层结构：
 * 1. core_memory（永驻 prompt）: importance >= CORE_THRESHOLD
 *    - 用户名字、关系、关键偏好、重要事件
 *    - 每次 LLM 调用都注入，不丢失
 *
 * 2. memory_items（按需召回）: importance 在 ITEMS_RANGE 区间
 *    - 一般性记忆，按重要度 + 时间排序
 *    - 通过 getRecentItems() 取 Top N 注入
 *    - 通过 recall(query) 按关键词召回
 *
 * 3. raw_history（原始对话历史）: 不在 MemoryStore
 *    - 由 ChatInteractor 直接查 ChatMessageDao
 *    - 作为最后兜底，保留最近 N 条原始消息
 *
 * 重要度分级（与 MemoryEntity 的 importance 字段对齐，1-5 范围）：
 * - importance >= 4: core_memory（永驻）
 * - importance 2-3: memory_items（按需召回）
 * - importance <= 1: 仅入库不主动注入（备查）
 *
 * 与 MochiBot 的差异：
 * - MochiBot 的 core_memory 是独立数据结构，本项目复用 MemoryEntity 表按 importance 分级
 * - 不修改表结构，仅通过查询阈值分级，向后兼容
 */
class MemoryStore(private val memoryDao: MemoryDao) {

    /**
     * 获取核心记忆（永驻 prompt）
     *
     * 使用场景：每次 LLM 调用都注入，确保关键事实不丢失
     * 数据来源：importance >= CORE_THRESHOLD 的所有记忆
     */
    suspend fun getCoreMemory(): List<MemoryEntity> {
        return try {
            memoryDao.getByMinImportance(CORE_THRESHOLD)
        } catch (e: Exception) {
            Log.e(TAG, "获取核心记忆失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 获取近期记忆项（按需注入）
     *
     * 使用场景：每次 LLM 调用注入 Top N 条，作为上下文参考
     * 数据来源：importance 在 ITEMS_RANGE 区间，按重要度+更新时间排序
     *
     * @param limit 返回条数，默认 10
     */
    suspend fun getRecentItems(limit: Int = DEFAULT_ITEMS_LIMIT): List<MemoryEntity> {
        return try {
            memoryDao.getByImportanceRange(
                min = ITEMS_RANGE_MIN,
                max = ITEMS_RANGE_MAX,
                limit = limit
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取近期记忆项失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 按关键词召回记忆（LLM 主动检索）
     *
     * 使用场景：LLM 通过 query_memory 工具主动检索历史记忆
     * 数据来源：全表关键词模糊匹配，按重要度排序
     *
     * @param query 查询关键词
     * @param limit 返回条数，默认 5
     */
    suspend fun recall(query: String, limit: Int = 5): List<MemoryEntity> {
        if (query.isBlank()) return emptyList()
        return try {
            val results = memoryDao.searchByKeyword(query, limit)
            // 命中后增加 accessCount，用于动态调整重要性
            results.forEach { memory ->
                memoryDao.incrementAccessCount(memory.id, System.currentTimeMillis())
            }
            Log.d(TAG, "召回记忆：query=\"$query\", 命中 ${results.size} 条")
            results
        } catch (e: Exception) {
            Log.e(TAG, "召回记忆失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 添加新记忆
     *
     * 根据 MemoryUpdate 的重要度自动分级入库
     * - importance >= 4: 进入 core_memory（下次 LLM 调用可见）
     * - importance 2-3: 进入 memory_items（按需召回）
     * - importance <= 1: 仅入库（备查）
     *
     * @param update LLM 输出的记忆更新
     * @param source 来源（chat/event/onboarding）
     */
    suspend fun addMemory(update: MemoryUpdate, source: String) {
        try {
            val now = System.currentTimeMillis()
            val entity = MemoryEntity(
                type = update.type,
                category = update.category,
                content = update.content,
                importance = update.importance.coerceIn(1, 5),
                source = source,
                createdAt = now,
                updatedAt = now
            )
            val id = memoryDao.insert(entity)
            val tier = when {
                update.importance >= CORE_THRESHOLD -> "core"
                update.importance >= ITEMS_RANGE_MIN -> "items"
                else -> "archive"
            }
            Log.d(TAG, "新增记忆 #$id [$tier] importance=${update.importance} type=${update.type}: ${update.content.take(50)}")
        } catch (e: Exception) {
            Log.e(TAG, "新增记忆失败: ${e.message}", e)
        }
    }

    /**
     * 提升记忆到核心层
     *
     * 使用场景：某条记忆被频繁召回时，自动提升重要度
     *
     * @param memoryId 记忆ID
     */
    suspend fun promoteToCore(memoryId: Long) {
        try {
            memoryDao.updateImportance(memoryId, CORE_THRESHOLD, System.currentTimeMillis())
            Log.d(TAG, "记忆 #$memoryId 已提升到核心层 (importance=$CORE_THRESHOLD)")
        } catch (e: Exception) {
            Log.e(TAG, "提升记忆重要度失败: ${e.message}", e)
        }
    }

    /**
     * 获取所有记忆（管理界面用）
     */
    suspend fun getAllMemories(): List<MemoryEntity> {
        return try {
            memoryDao.getTopMemories(100)
        } catch (e: Exception) {
            Log.e(TAG, "获取所有记忆失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 清理低重要度记忆（容量管理）
     */
    suspend fun cleanupLowImportance() {
        try {
            val deleted = memoryDao.deleteLowImportance(1)
            Log.d(TAG, "清理低重要度记忆完成")
        } catch (e: Exception) {
            Log.e(TAG, "清理低重要度记忆失败: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "MemoryStore"

        /** 核心记忆阈值：importance >= 4 进入 core_memory（永驻 prompt） */
        const val CORE_THRESHOLD = 4

        /** 记忆项区间：importance 2-3 进入 memory_items（按需召回） */
        const val ITEMS_RANGE_MIN = 2
        const val ITEMS_RANGE_MAX = 4  // 不含 4，4 及以上是 core

        /** 默认记忆项注入条数 */
        const val DEFAULT_ITEMS_LIMIT = 10
    }
}
