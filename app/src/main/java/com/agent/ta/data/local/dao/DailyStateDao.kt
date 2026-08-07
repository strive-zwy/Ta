package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.agent.ta.data.local.entity.DailyStateEntity

@Dao
interface DailyStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DailyStateEntity): Long

    @Update
    suspend fun update(entity: DailyStateEntity)

    @Query("SELECT * FROM daily_state WHERE agentId = :agentId AND date = :date LIMIT 1")
    suspend fun getByDate(agentId: Long, date: String): DailyStateEntity?

    @Query("SELECT * FROM daily_state WHERE agentId = :agentId AND date >= :startDate AND date <= :endDate ORDER BY date DESC")
    suspend fun getRange(agentId: Long, startDate: String, endDate: String): List<DailyStateEntity>

    @Query("SELECT * FROM daily_state WHERE agentId = :agentId ORDER BY date DESC LIMIT :limit")
    suspend fun getRecent(agentId: Long, limit: Int = 7): List<DailyStateEntity>

    /**
     * upsert 但保留 createdAt
     * 用于 DailySummaryGenerator 更新当日状态时不丢失创建时间
     */
    @Transaction
    suspend fun upsertPreservingCreatedAt(entity: DailyStateEntity) {
        val existing = getByDate(entity.agentId, entity.date)
        if (existing != null) {
            update(entity.copy(createdAt = existing.createdAt))
        } else {
            insert(entity)
        }
    }

    @Query("DELETE FROM daily_state WHERE agentId = :agentId AND date < :beforeDate")
    suspend fun deleteBefore(agentId: Long, beforeDate: String): Int

    @Query("DELETE FROM daily_state WHERE agentId = :agentId")
    suspend fun deleteAll(agentId: Long)
}
