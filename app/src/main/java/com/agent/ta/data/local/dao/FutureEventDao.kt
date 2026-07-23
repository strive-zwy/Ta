package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agent.ta.data.local.entity.FutureEventEntity

@Dao
interface FutureEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FutureEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<FutureEventEntity>): List<Long>

    @Query("SELECT * FROM future_events WHERE date >= :fromDate AND date <= :toDate ORDER BY date ASC")
    suspend fun getRange(fromDate: String, toDate: String): List<FutureEventEntity>

    @Query("SELECT * FROM future_events WHERE date = :date ORDER BY createdAt ASC")
    suspend fun getByDate(date: String): List<FutureEventEntity>

    @Query("SELECT * FROM future_events ORDER BY date ASC")
    suspend fun getAll(): List<FutureEventEntity>

    @Query("DELETE FROM future_events WHERE date < :beforeDate")
    suspend fun deleteBefore(beforeDate: String): Int

    @Query("UPDATE future_events SET consumed = 1 WHERE id = :id")
    suspend fun markConsumed(id: Long)

    @Query("DELETE FROM future_events")
    suspend fun deleteAll()
}
