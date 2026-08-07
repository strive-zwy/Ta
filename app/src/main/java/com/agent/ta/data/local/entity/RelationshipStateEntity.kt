package com.agent.ta.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Agent 与用户的关系状态（每个 Agent 一条记录，主键为 agentId）
 *
 * 5 阶段（currentStage）：
 * - stranger（陌生，intimacy 0-15）
 * - acquaintance（初识，16-35）
 * - familiar（熟悉，36-60）
 * - intimate（亲密，61-85）
 * - confidant（知己，86-100）
 *
 * 数值推进策略：
 * - intimacyScore：对话轮驱动，每轮 +0.5~2（按情绪氛围加权）
 * - trustScore：每轮 = intimacy 增量 × 0.6，每日 -0.5 衰减
 * - interactionCount：累计对话轮数
 */
@Entity(tableName = "relationship_state")
data class RelationshipStateEntity(
    @PrimaryKey
    val agentId: Long,             // 所属 Agent 实例 ID（多 Agent 数据隔离，每个 Agent 一条）
    val currentStage: String,      // "stranger" / "acquaintance" / "familiar" / "intimate" / "confidant"
    val intimacyScore: Int,        // 0-100 亲密度
    val trustScore: Int,           // 0-100 信任度
    val interactionCount: Int,     // 累计对话轮数
    val lastInteractionAt: Long,   // 上次对话时间戳
    val lastDecayAt: Long,         // 上次 trust 衰减时间戳（用于判断今日是否已衰减）
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
