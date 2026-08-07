package com.agent.ta.data.local.entity

import androidx.room.Entity

/**
 * 结构化每日状态记录
 *
 * 记录每天的结构化状态参数，供 L2 昨日延续使用。
 * 由 DailySummaryGenerator 在生成每日摘要时同步生成。
 *
 * 复合主键 (agentId, date)：不同 Agent 同一天可有独立状态记录。
 */
@Entity(
    tableName = "daily_state",
    primaryKeys = ["agentId", "date"]
)
data class DailyStateEntity(
    /** 所属 Agent 实例 ID（多 Agent 数据隔离） */
    val agentId: Long,
    val date: String,                  // "yyyy-MM-dd"
    val sleepTime: String?,            // "01:15" - 昨晚睡觉时间（从最后一个 unavailable slot 的 start 提取）
    val wakeTime: String?,             // "07:30" - 今早起床时间
    val sleepDurationMin: Int?,         // 360 - 实际睡眠时长（分钟）
    val mood: Float?,                  // -1.0~1.0（昨日情绪）
    val fatigue: Float?,               // 0.0-1.0（昨日疲劳）
    val stress: Float?,                // 0.0-1.0（昨日压力）
    val energy: Float?,                // 0.0-1.0（昨日精力水平）
    val mainActivities: String,        // JSON 数组字符串 ["赶设计稿","和朋友吃饭"]
    val specialEvents: String,         // JSON 数组字符串 ["生日","约会"]
    val hadInteractionWithUser: Boolean, // 当天是否和用户互动
    val interactionCount: Int,          // 互动消息数
    val summary: String,               // 语义化总结（LLM 生成，100-200 字）
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
