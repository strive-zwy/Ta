package com.agent.ta.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Agent 记忆条目
 * 和用户共同成长的记忆，分为用户画像、事件、偏好、关系等类型
 */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String,              // user_profile | event | preference | relationship
    val category: String,         // 如 "喜好"、"工作"、"家庭"、"共同经历"
    val content: String,          // 记忆内容文本
    val importance: Int = 3,      // 重要程度 1-5
    val source: String,           // onboarding | chat | event
    val createdAt: Long,
    val updatedAt: Long,
    val accessCount: Int = 0      // 被引用次数（用于动态调整重要性）
)
