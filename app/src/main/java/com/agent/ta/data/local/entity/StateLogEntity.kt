package com.agent.ta.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 状态切换日志
 */
@Entity(tableName = "state_log")
data class StateLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 所属 Agent 实例 ID（多 Agent 数据隔离） */
    val agentId: Long,
    val state: String,              // 状态 ID
    val enteredAt: Long,            // 进入时间戳
    val exitedAt: Long? = null      // 离开时间戳
)
