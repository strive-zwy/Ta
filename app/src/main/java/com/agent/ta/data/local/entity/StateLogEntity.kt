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
    val state: String,              // 状态 ID
    val enteredAt: Long,            // 进入时间戳
    val exitedAt: Long? = null      // 离开时间戳
)
