package com.agent.ta.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 关系里程碑事件
 *
 * 触发来源（triggerSource）：
 * - llm_declared：LLM 在 reply.milestoneDeclared 字段主动声明
 * - engine_detected：RelationshipEngine 检测到行为模式自动触发（兜底）
 *
 * 常见 type：
 * - first_vulnerability（第一次袒露脆弱）
 * - first_argument（第一次争吵）
 * - first_secret_shared（第一次分享秘密）
 * - first_initiative_care（第一次主动关心）
 * - first_emoji_to_user（第一次对你用表情）
 * - late_night_confidant（愿深夜相伴，深夜倾诉 3 次后触发）
 * - consistent_chat（持续陪伴，连续 3 天对话 ≥ 10 轮）
 * - stage_transition_to_acquaintance（阶段切换：进入初识）
 * - stage_transition_to_familiar / intimate / confidant
 */
@Entity(
    tableName = "milestone_events",
    indices = [Index(value = ["agentId", "type", "triggeredAt"])]
)
data class MilestoneEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 所属 Agent 实例 ID（多 Agent 数据隔离） */
    val agentId: Long,
    val type: String,              // "first_vulnerability" / "stage_transition_to_intimate" 等
    val title: String,            // 显示名，如"第一次袒露脆弱"
    val triggeredAt: Long,        // 触发时间戳
    val triggerSource: String,    // "llm_declared" / "engine_detected"
    val contextSnapshot: String    // JSON 快照，记录触发时上下文（如 replyText 摘要）
)
