package com.agent.ta.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 当天作息规划（每天一条，由 LLM 生成，可随时调整）
 *
 * - date: 日期 "yyyy-MM-dd"，唯一
 * - slotsJson: DailySlot 列表的 JSON
 * - isAdjusted: 是否被 Agent 主动调整过
 * - source: "plan" 首次规划 / "adjust" 调整过
 */
@Entity(tableName = "daily_schedule")
data class DailyScheduleEntity(
    @PrimaryKey
    val date: String,
    val slotsJson: String,
    val isAdjusted: Boolean = false,
    val source: String = "plan",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
