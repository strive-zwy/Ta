package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agent.ta.data.local.entity.StateLogEntity

@Dao
interface StateLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: StateLogEntity): Long

    @Query("SELECT * FROM state_log ORDER BY enteredAt DESC LIMIT 1")
    suspend fun getLatest(): StateLogEntity?

    @Query("UPDATE state_log SET exitedAt = :exitedAt WHERE id = :id")
    suspend fun updateExit(id: Long, exitedAt: Long)

    @Query("DELETE FROM state_log")
    suspend fun deleteAll()
}
