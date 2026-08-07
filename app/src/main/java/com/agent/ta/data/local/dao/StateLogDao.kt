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

    @Query("SELECT * FROM state_log WHERE agentId = :agentId ORDER BY enteredAt DESC LIMIT 1")
    suspend fun getLatest(agentId: Long): StateLogEntity?

    @Query("UPDATE state_log SET exitedAt = :exitedAt WHERE agentId = :agentId AND id = :id")
    suspend fun updateExit(agentId: Long, id: Long, exitedAt: Long)

    @Query("DELETE FROM state_log WHERE agentId = :agentId")
    suspend fun deleteAll(agentId: Long)
}
