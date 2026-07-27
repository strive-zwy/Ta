package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agent.ta.data.local.entity.DailyScheduleEntity

@Dao
interface DailyScheduleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailyScheduleEntity)

    @Query("SELECT * FROM daily_schedule WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): DailyScheduleEntity?

    @Query("SELECT * FROM daily_schedule ORDER BY date DESC LIMIT 1")
    suspend fun getLatest(): DailyScheduleEntity?

    /**
     * 查询指定日期范围内的作息记录（含两端，按日期倒序）
     * 用于 DailyPlanner 注入近 N 天作息历史，避免每天作息重复
     */
    @Query("SELECT * FROM daily_schedule WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC")
    suspend fun getRange(startDate: String, endDate: String): List<DailyScheduleEntity>

    @Query("DELETE FROM daily_schedule WHERE date < :beforeDate")
    suspend fun deleteBefore(beforeDate: String): Int

    @Query("DELETE FROM daily_schedule")
    suspend fun deleteAll()
}
