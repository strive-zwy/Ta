package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.agent.ta.data.local.entity.DailyScheduleEntity

@Dao
interface DailyScheduleDao {

    @Insert
    suspend fun insert(entity: DailyScheduleEntity)

    @Update
    suspend fun update(entity: DailyScheduleEntity)

    /**
     * 插入或更新作息记录，保留原始 createdAt
     *
     * 使用 @Transaction 包裹"先查后写"，避免 REPLACE 策略删除旧行再插入新行
     * 导致 createdAt 被覆盖为当前时间、丢失原始创建时间的问题。
     *
     * **注意**：此方法会覆盖 originalSlotsJson，仅用于首次生成。调整作息请用
     * [updateActualSlots]，后者保留 originalSlotsJson 不变。
     */
    @Transaction
    suspend fun upsertPreservingCreatedAt(entity: DailyScheduleEntity) {
        val existing = getByDate(entity.agentId, entity.date)
        if (existing == null) {
            insert(entity)
        } else {
            update(entity.copy(createdAt = existing.createdAt))
        }
    }

    /**
     * 仅更新实际作息（slotsJson），保留 originalSlotsJson 和 createdAt 不变
     *
     * 用于 ScheduleAdjuster 调整作息：只动实际作息，不动原始计划快照。
     * 同时更新 isAdjusted=true / source="adjust" / updatedAt。
     */
    @Transaction
    suspend fun updateActualSlots(
        agentId: Long,
        date: String,
        newSlotsJson: String,
        source: String = "adjust"
    ) {
        val existing = getByDate(agentId, date) ?: return
        update(
            existing.copy(
                slotsJson = newSlotsJson,
                isAdjusted = true,
                source = source,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    @Query("SELECT * FROM daily_schedule WHERE agentId = :agentId AND date = :date LIMIT 1")
    suspend fun getByDate(agentId: Long, date: String): DailyScheduleEntity?

    @Query("SELECT * FROM daily_schedule WHERE agentId = :agentId ORDER BY date DESC LIMIT 1")
    suspend fun getLatest(agentId: Long): DailyScheduleEntity?

    /**
     * 查询指定日期范围内的作息记录（含两端，按日期倒序）
     * 用于 DailyPlanner 注入近 N 天作息历史，避免每天作息重复
     */
    @Query("SELECT * FROM daily_schedule WHERE agentId = :agentId AND date >= :startDate AND date <= :endDate ORDER BY date DESC")
    suspend fun getRange(agentId: Long, startDate: String, endDate: String): List<DailyScheduleEntity>

    @Query("DELETE FROM daily_schedule WHERE agentId = :agentId AND date < :beforeDate")
    suspend fun deleteBefore(agentId: Long, beforeDate: String): Int

    @Query("DELETE FROM daily_schedule WHERE agentId = :agentId")
    suspend fun deleteAll(agentId: Long)
}
