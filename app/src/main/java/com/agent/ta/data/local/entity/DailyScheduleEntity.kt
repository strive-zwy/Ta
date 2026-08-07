package com.agent.ta.data.local.entity

import androidx.room.Entity

/**
 * 当天作息规划（每个 Agent 每天一条，由 LLM 生成，可随时调整）
 *
 * - 复合主键 (agentId, date)：不同 Agent 同一天可有独立作息
 * - slotsJson: 实际作息（DailySlot 列表 JSON，会被 ScheduleAdjuster 覆盖更新）
 * - originalSlotsJson: 原始计划作息快照（首次生成时写入，当天不可变，用于"计划 vs 实际"对比与反思）
 * - isAdjusted: 是否被 Agent 主动调整过
 * - source: "plan" 首次规划 / "adjust" 调整过 / "fallback" 兜底
 */
@Entity(
    tableName = "daily_schedule",
    primaryKeys = ["agentId", "date"]
)
data class DailyScheduleEntity(
    /** 所属 Agent 实例 ID（多 Agent 数据隔离） */
    val agentId: Long,
    val date: String,
    val slotsJson: String,
    val originalSlotsJson: String = "",
    val isAdjusted: Boolean = false,
    val source: String = "plan",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
