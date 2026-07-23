package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agent.ta.data.local.entity.AgentConfigEntity

@Dao
interface AgentConfigDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AgentConfigEntity): Long

    @Query("SELECT * FROM agent_config WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): AgentConfigEntity?

    @Query("UPDATE agent_config SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE agent_config SET configJson = :json, agentName = :name WHERE isActive = 1")
    suspend fun updateActive(json: String, name: String)

    @Query("DELETE FROM agent_config WHERE id = :id")
    suspend fun deleteById(id: Long)
}
