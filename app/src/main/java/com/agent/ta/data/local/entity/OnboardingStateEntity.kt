package com.agent.ta.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Onboarding 阶段记录
 * 记录 Agent 了解用户的进度
 */
@Entity(tableName = "onboarding_state")
data class OnboardingStateEntity(
    @PrimaryKey
    val id: Int = 1,               // 固定单行
    val phase: String,             // not_started | in_progress | completed
    val currentStep: Int = 0,      // 当前第几轮对话
    val totalSteps: Int = 4,       // 总共几轮（默认 4 轮）
    val startedAt: Long? = null,
    val completedAt: Long? = null
)
