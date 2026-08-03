package com.agent.ta.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 当天作息规划（每天一条，由 LLM 生成，可随时调整）
 *
 * - date: 日期 "yyyy-MM-dd"，唯一
 * - slotsJson: 实际作息（DailySlot 列表 JSON，会被 ScheduleAdjuster 覆盖更新）
 * - originalSlotsJson: 原始计划作息快照（首次生成时写入，当天不可变，用于"计划 vs 实际"对比与反思）
 * - isAdjusted: 是否被 Agent 主动调整过
 * - source: "plan" 首次规划 / "adjust" 调整过 / "fallback" 兜底
 */
@Entity(tableName = "daily_schedule")
data class DailyScheduleEntity(
    @PrimaryKey
    val date: String,
    val slotsJson: String,
    val originalSlotsJson: String = "",
    val isAdjusted: Boolean = false,
    val source: String = "plan",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
