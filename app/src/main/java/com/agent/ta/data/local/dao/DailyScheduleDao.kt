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

    @Query("DELETE FROM daily_schedule WHERE date < :beforeDate")
    suspend fun deleteBefore(beforeDate: String): Int

    @Query("DELETE FROM daily_schedule")
    suspend fun deleteAll()
}
