package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agent.ta.data.local.entity.MilestoneEventEntity

@Dao
interface MilestoneEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: MilestoneEventEntity): Long

    @Query("SELECT * FROM milestone_events ORDER BY triggeredAt DESC")
    suspend fun getAll(): List<MilestoneEventEntity>

    /**
     * 按 type 查询（用于去重判断，避免同一里程碑重复触发）
     */
    @Query("SELECT * FROM milestone_events WHERE type = :type ORDER BY triggeredAt DESC")
    suspend fun getByType(type: String): List<MilestoneEventEntity>

    /**
     * 统计某 type 在指定时间戳之后出现的次数
     * 用于 Engine 检测模式，如"深夜倾诉 3 次"
     */
    @Query("SELECT COUNT(*) FROM milestone_events WHERE type = :type AND triggeredAt >= :since")
    suspend fun countByTypeSince(type: String, since: Long): Int

    /**
     * 获取最近的 N 条里程碑（供 prompt 注入）
     */
    @Query("SELECT * FROM milestone_events ORDER BY triggeredAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MilestoneEventEntity>

    /**
     * 清理旧里程碑（90 天前）
     */
    @Query("DELETE FROM milestone_events WHERE triggeredAt < :beforeTs")
    suspend fun deleteOldBefore(beforeTs: Long): Int
}
