package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agent.ta.data.local.entity.ConfigSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConfigSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConfigSessionEntity)

    @Query("SELECT * FROM config_sessions WHERE agentId = :agentId")
    suspend fun getByAgentId(agentId: Long): ConfigSessionEntity?

    @Query("SELECT * FROM config_sessions WHERE agentId = :agentId")
    fun observeByAgentId(agentId: Long): Flow<ConfigSessionEntity?>

    @Query("UPDATE config_sessions SET stage = :toStage, updatedAt = :updatedAt WHERE agentId = :agentId AND stage = :fromStage")
    suspend fun updateStageIf(agentId: Long, fromStage: String, toStage: String, updatedAt: Long): Int

    @Query("DELETE FROM config_sessions WHERE agentId = :agentId")
    suspend fun deleteByAgentId(agentId: Long)
}
