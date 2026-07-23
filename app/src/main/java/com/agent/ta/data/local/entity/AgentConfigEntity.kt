package com.agent.ta.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Agent 配置实体（当前生效的配置）
 */
@Entity(tableName = "agent_config")
data class AgentConfigEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val configJson: String,         // 完整 agent.json
    val agentName: String,          // Agent 名称（冗余，方便查询）
    val importedAt: Long,           // 导入时间戳
    val isActive: Boolean = true    // 是否当前激活
)
