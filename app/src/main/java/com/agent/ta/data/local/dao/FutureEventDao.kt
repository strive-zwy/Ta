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

    @Query("SELECT * FROM future_events WHERE agentId = :agentId AND date >= :fromDate AND date <= :toDate ORDER BY date ASC")
    suspend fun getRange(agentId: Long, fromDate: String, toDate: String): List<FutureEventEntity>

    @Query("SELECT * FROM future_events WHERE agentId = :agentId AND date = :date ORDER BY createdAt ASC")
    suspend fun getByDate(agentId: Long, date: String): List<FutureEventEntity>

    @Query("SELECT * FROM future_events WHERE agentId = :agentId ORDER BY date ASC")
    suspend fun getAll(agentId: Long): List<FutureEventEntity>

    @Query("DELETE FROM future_events WHERE agentId = :agentId AND date < :beforeDate")
    suspend fun deleteBefore(agentId: Long, beforeDate: String): Int

    @Query("UPDATE future_events SET consumed = 1 WHERE agentId = :agentId AND id = :id")
    suspend fun markConsumed(agentId: Long, id: Long)

    @Query("DELETE FROM future_events WHERE agentId = :agentId")
    suspend fun deleteAll(agentId: Long)
}
