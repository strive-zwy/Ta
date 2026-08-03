package com.agent.ta.domain

import com.agent.ta.data.local.entity.MilestoneEventEntity
import com.agent.ta.data.local.entity.RelationshipStateEntity
import com.agent.ta.data.model.RelationshipStage
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * 关系状态推进引擎（纯计算，无 DB 副作用）
 *
 * 推进策略：
 * - 对话轮驱动 intimacy：基础 +0.5，按情绪加权（happy ×1.5、sad/vulnerable ×2.0、angry ×0.3）
 * - 长消息加权：>50 字 ×1.2，>200 字 ×1.5
 * - trust 增量 = intimacy 增量 × 0.6（信任积累比亲密慢）
 * - 每日衰减：trust -0.5，intimacy -0.2（防止长期不互动时数值停滞）
 */
class RelationshipEngine {

    /**
     * 一轮对话的上下文输入
     */
    data class TurnContext(
        val emotion: String,
        val isUserInitiated: Boolean,
        val messageLength: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 一轮对话结束后的更新结果
     */
    data class RelationshipUpdate(
        val intimacyIncrement: Double,
        val trustIncrement: Double,
        val newInteractionCount: Int,
        val stageTransition: RelationshipStage?  // 若发生阶段切换则非 null
    )

    /**
     * 计算一轮对话结束后关系的增量
     */
    fun applyTurnEnd(ctx: TurnContext, currentState: RelationshipStateEntity): RelationshipUpdate {
        val emotionWeight = emotionWeight(ctx.emotion)
        val lengthWeight = lengthWeight(ctx.messageLength)
        val baseIncrement = 0.5
        val intimacyIncrement = baseIncrement * emotionWeight * lengthWeight
        val trustIncrement = intimacyIncrement * 0.6  // 信任积累比亲密慢
        val newInteractionCount = currentState.interactionCount + 1

        val oldIntimacy = currentState.intimacyScore
        val newIntimacy = (oldIntimacy + intimacyIncrement).coerceIn(0.0, 100.0).toInt()
        val stageTransition = checkStageTransition(oldIntimacy, newIntimacy)

        return RelationshipUpdate(
            intimacyIncrement = intimacyIncrement,
            trustIncrement = trustIncrement,
            newInteractionCount = newInteractionCount,
            stageTransition = stageTransition
        )
    }

    /**
     * 应用每日衰减
     * trustScore -0.5，intimacyScore -0.2（防止数值长期停滞）
     */
    fun applyDailyDecay(state: RelationshipStateEntity): RelationshipStateEntity {
        val newTrust = (state.trustScore - 0.5).coerceIn(0.0, 100.0).toInt()
        val newIntimacy = (state.intimacyScore - 0.2).coerceIn(0.0, 100.0).toInt()
        val now = System.currentTimeMillis()
        return state.copy(
            trustScore = newTrust,
            intimacyScore = newIntimacy,
            lastDecayAt = now,
            updatedAt = now
        )
    }

    /**
     * 检测是否跨越阶段边界
     * @return 若跨越则返回新阶段，否则 null
     */
    fun checkStageTransition(oldScore: Int, newScore: Int): RelationshipStage? {
        if (oldScore == newScore) return null
        val oldStage = RelationshipStage.fromScore(oldScore)
        val newStage = RelationshipStage.fromScore(newScore)
        return if (oldStage != newStage) newStage else null
    }

    /**
     * Engine 兜底检测：基于行为模式触发里程碑
     * @param ctx 当前对话上下文
     * @param recentMilestones 最近里程碑列表（用于判断是否已触发）
     * @param recentTurnCount 最近对话轮数（用于"连续 3 天对话 ≥ 10 轮"等判断）
     * @return 触发的里程碑 type，若无需触发则 null
     */
    fun shouldTriggerMilestoneByPattern(
        ctx: TurnContext,
        recentMilestones: List<MilestoneEventEntity>,
        recentTurnCount: Int
    ): String? {
        // 深夜倾诉模式：22:00-02:00 期间对话，且累计倾诉 3 次以上，且未触发过 "late_night_confidant"
        val hour = Instant.ofEpochMilli(ctx.timestamp)
            .atZone(ZoneId.of("Asia/Shanghai"))
            .hour
        val isLateNight = hour in 22..23 || hour in 0..2
        if (isLateNight && recentTurnCount >= 3) {
            val alreadyTriggered = recentMilestones.any { it.type == "late_night_confidant" }
            if (!alreadyTriggered) return "late_night_confidant"
        }

        // 持续陪伴模式：连续对话轮数 ≥ 10
        // 注意：完整的"连续 3 天"判断需要查 DailyState 表，这里只做轮数阈值
        // 实际触发由 RelationshipService 在跨天检查时调用
        return null
    }

    /**
     * 情绪权重映射
     */
    private fun emotionWeight(emotion: String): Double {
        return when (emotion.lowercase().trim()) {
            "happy", "joyful", "cheerful", "excited" -> 1.5
            "sad", "vulnerable", "lonely", "melancholy" -> 2.0
            "angry", "annoyed", "frustrated" -> 0.3
            "neutral", "calm" -> 1.0
            else -> 1.0  // 未知情绪默认权重
        }
    }

    /**
     * 消息长度权重
     */
    private fun lengthWeight(length: Int): Double {
        return when {
            length > 200 -> 1.5
            length > 50 -> 1.2
            else -> 1.0
        }
    }
}
