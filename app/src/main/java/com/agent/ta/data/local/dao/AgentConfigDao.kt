package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.agent.ta.data.local.entity.AgentConfigEntity

@Dao
interface AgentConfigDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AgentConfigEntity): Long

    @Query("SELECT * FROM agent_config WHERE id = :id")
    suspend fun getById(id: Long): AgentConfigEntity?

    @Query("SELECT * FROM agent_config WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): AgentConfigEntity?

    /**
     * 确定性查询当前应激活的 Agent：
     * 优先 isActive=1 的记录；若无则按 importedAt DESC, id DESC 选择最近导入的实例作为 fallback。
     */
    @Query("SELECT * FROM agent_config ORDER BY isActive DESC, importedAt DESC, id DESC LIMIT 1")
    suspend fun getActiveDeterministic(): AgentConfigEntity?

    @Query("UPDATE agent_config SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE agent_config SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: Long)

    /** 原子地停用全部并激活指定 ID（事务保证同一时刻只有一个激活实例） */
    @Transaction
    suspend fun activateById(id: Long) {
        deactivateAll()
        setActive(id)
    }

    @Query("SELECT COUNT(*) FROM agent_config WHERE isActive = 1")
    suspend fun countActive(): Int

    @Query("UPDATE agent_config SET configJson = :json, agentName = :name WHERE isActive = 1")
    suspend fun updateActive(json: String, name: String)

    @Query("UPDATE agent_config SET configJson = :json, agentName = :name WHERE id = :id")
    suspend fun updateById(id: Long, json: String, name: String)

    @Query("DELETE FROM agent_config WHERE id = :id")
    suspend fun deleteById(id: Long)
}
