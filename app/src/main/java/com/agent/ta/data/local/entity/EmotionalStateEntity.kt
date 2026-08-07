package com.agent.ta.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Agent 情绪状态（每个 Agent 一条记录，主键为 agentId）
 *
 * Phase 3 情感势能驱动主动发起系统的核心数据模型。
 * 与 RelationshipStateEntity（Phase 2 关系系统）完全解耦，互不干扰。
 *
 * 字段说明：
 * - valence：效价，-1.0(苦) ~ 1.0(乐)，表示 Agent 当下的愉悦程度
 * - arousal：唤醒度，0.0(平静) ~ 1.0(激动)，表示 Agent 当下的激动程度
 * - potentialEnergy：势能，0-100，未表达情绪积累，超阈值驱动主动发起
 * - lastEmotion：最近一次 LLM 自报的情绪标签（happy/sad/angry/neutral...）
 * - lastUserInteractionAt：上次用户互动时间戳，用于计算静默时长
 * - lastDecayAt：上次势能衰减时间戳，用于每小时衰减检查
 */
@Entity(tableName = "emotional_state")
data class EmotionalStateEntity(
    @PrimaryKey
    val agentId: Long,                     // 所属 Agent 实例 ID（多 Agent 数据隔离，每个 Agent 一条）
    val valence: Float,                    // -1.0(苦) ~ 1.0(乐) 效价
    val arousal: Float,                    // 0.0(平静) ~ 1.0(激动) 唤醒度
    val potentialEnergy: Int,              // 0-100 未表达情绪积累
    val lastEmotion: String?,              // 最近 LLM 自报情绪标签
    val lastUserInteractionAt: Long,       // 上次用户互动时间戳（用于静默积累）
    val lastDecayAt: Long,                 // 上次势能衰减时间戳
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
