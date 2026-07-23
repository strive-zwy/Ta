package com.agent.ta.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 未来事件（Agent 提前知道的日程参考）
 *
 * 来源：
 * - 用户在聊天中提到（"后天 Taylor Swift 有演唱会"）→ LLM 提取存入
 * - Admin 端配置的 referenceCelebrity 相关日程
 * - 其他途径获取的未来事件
 *
 * 用途：
 * - DailyPlanner 生成当天作息时，查询今天/近期的事件作为 prompt 参考
 * - 事件过期后自动清理
 */
@Entity(tableName = "future_events")
data class FutureEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 事件日期 "yyyy-MM-dd" */
    val date: String,
    /** 事件描述（如"Taylor Swift 上海演唱会"） */
    val description: String,
    /** 来源："chat" 聊天提取 / "manual" 手动添加 / "config" 配置导入 */
    val source: String = "chat",
    /** 是否已被 DailyPlanner 纳入过作息规划 */
    val consumed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
