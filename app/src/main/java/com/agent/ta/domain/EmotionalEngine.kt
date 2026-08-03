package com.agent.ta.domain

import com.agent.ta.data.local.entity.DailyStateEntity
import com.agent.ta.data.local.entity.EmotionalStateEntity
import kotlin.math.abs

/**
 * 情绪引擎核心计算（Phase 3 情感势能驱动主动发起）
 *
 * 纯函数集合，不依赖 DAO/ServiceLocator，方便单元测试。
 * 业务编排由 [EmotionalService] 封装。
 *
 * 核心机制：
 * 1. 对话轮驱动：LLM 自报 emotionIntensity(-2~+2) → valence 漂移 + arousal 推高 + 势能累加
 * 2. 静默积累：每小时按 Agent 当前 valence 系数积累势能（开心 ×1.5 / 平静 ×1.0 / 低落 ×0.5 / 心灰×(-1.0)）
 * 3. 每小时衰减：势能 -2，valence 向 0 漂移 0.05，arousal 向 0.3 漂移 0.03
 * 4. 睡眠基线：跨天读取昨日 daily_state，sleepDurationMin<360 → valence -0.3, arousal +0.2
 */
object EmotionalEngine {

    /**
     * 对话轮驱动：根据 LLM 自报的情绪强度更新 valence/arousal/势能
     *
     * @param intensity -2.0(强烈负面) ~ +2.0(强烈兴奋)，0 表示平静
     * @param emotion 情绪标签（happy/sad/angry/neutral...）
     * @param current 当前情绪状态
     * @return 新的 valence/arousal/势能增量 + 情绪标签
     */
    fun applyTurnEnd(
        intensity: Float,
        emotion: String,
        current: EmotionalStateEntity
    ): EmotionalUpdate {
        // valence 缓慢跟随情绪强度方向（intensity 映射到 -1~1）
        val emotionValence = intensity.coerceIn(-1f, 1f)
        val newValence = current.valence * 0.7f + emotionValence * 0.3f

        // arousal 受强度绝对值推高（强度越大越激动）
        val newArousal = (current.arousal + abs(intensity) * 0.2f).coerceIn(0f, 1f)

        // 势能增量：强度越大积累越多（|intensity| × 8，最大 16）
        val energyIncrement = (abs(intensity) * 8).toInt()

        return EmotionalUpdate(
            newValence = newValence,
            newArousal = newArousal,
            energyIncrement = energyIncrement,
            newLastEmotion = emotion
        )
    }

    /**
     * 静默积累：根据 Agent 当前 valence 计算每小时势能增量
     *
     * 规则：
     * - valence > 0.5（开心）→ +7（系数 1.5 × base 5）
     * - valence 0~0.5（平静）→ +5
     * - valence -0.5~0（低落）→ +2（系数 0.5 × base 5，向下取整）
     * - valence < -0.5 且静默 > 4h → -5（心灰意冷，越等越不想说）
     * - valence < -0.5 且静默 ≤ 4h → +2（仍给用户哄人窗口）
     *
     * @param current 当前情绪状态
     * @param now 当前时间戳
     * @return 势能增量 + 是否为衰减模式
     */
    fun applySilentAccumulation(
        current: EmotionalStateEntity,
        now: Long
    ): SilentUpdate {
        val silentMs = now - current.lastUserInteractionAt
        val silentHours = silentMs / 3_600_000L

        return when {
            current.valence > 0.5f -> SilentUpdate(energyDelta = 7, isDecay = false)
            current.valence >= 0f -> SilentUpdate(energyDelta = 5, isDecay = false)
            current.valence >= -0.5f -> SilentUpdate(energyDelta = 2, isDecay = false)
            silentHours >= 4 -> SilentUpdate(energyDelta = -5, isDecay = true)
            else -> SilentUpdate(energyDelta = 2, isDecay = false)
        }
    }

    /**
     * 每小时衰减：势能 -2，valence 向中性(0)漂移，arousal 向基线(0.3)漂移
     *
     * @param current 当前情绪状态
     * @return 新的势能/valence/arousal
     */
    fun applyHourlyDecay(current: EmotionalStateEntity): DecayUpdate {
        // 势能每小时 -2，下限 0
        val newEnergy = (current.potentialEnergy - 2).coerceAtLeast(0)

        // valence 向 0 漂移 0.05
        val newValence = if (current.valence > 0f) {
            (current.valence - 0.05f).coerceAtLeast(0f)
        } else {
            (current.valence + 0.05f).coerceAtMost(0f)
        }

        // arousal 向 0.3 漂移 0.03
        val newArousal = if (current.arousal > 0.3f) {
            (current.arousal - 0.03f).coerceAtLeast(0.3f)
        } else {
            (current.arousal + 0.03f).coerceAtMost(0.3f)
        }

        return DecayUpdate(
            newEnergy = newEnergy,
            newValence = newValence,
            newArousal = newArousal
        )
    }

    /**
     * 睡眠基线：跨天时读取昨日 daily_state，把睡眠情况映射为今天起始情绪
     *
     * 规则：
     * - yesterdayState == null → 保持中性（valence=0, arousal=0.3）
     * - sleepDurationMin < 360（睡不够 6h）→ valence=-0.3, arousal=0.5（易怒）
     * - fatigue > 0.7（昨日疲劳度高）→ arousal 再 -0.2（叠加，保留下限 0.1）
     *
     * @param yesterdayState 昨日 daily_state，可能为 null
     * @param current 当前情绪状态（用于保留其他字段）
     * @return 应用睡眠基线后的新情绪状态
     */
    fun applySleepBaseline(
        yesterdayState: DailyStateEntity?,
        current: EmotionalStateEntity
    ): EmotionalStateEntity {
        if (yesterdayState == null) {
            // 无昨日数据，用中性默认值
            return current.copy(
                valence = 0f,
                arousal = 0.3f,
                updatedAt = System.currentTimeMillis()
            )
        }

        var newValence = 0f           // 默认中性
        var newArousal = 0.3f         // 默认基线

        // 睡眠不足 → 易怒（valence -0.3, arousal +0.2）
        if (yesterdayState.sleepDurationMin != null && yesterdayState.sleepDurationMin < 360) {
            newValence = -0.3f
            newArousal = 0.5f
        }

        // 昨日疲劳度高 → 精神不振（arousal 再 -0.2，保留下限 0.1）
        if (yesterdayState.fatigue != null && yesterdayState.fatigue > 0.7f) {
            newArousal = (newArousal - 0.2f).coerceAtLeast(0.1f)
        }

        return current.copy(
            valence = newValence,
            arousal = newArousal,
            updatedAt = System.currentTimeMillis()
        )
    }
}

/** 对话轮驱动计算结果 */
data class EmotionalUpdate(
    val newValence: Float,
    val newArousal: Float,
    val energyIncrement: Int,
    val newLastEmotion: String
)

/** 静默积累计算结果 */
data class SilentUpdate(
    val energyDelta: Int,
    val isDecay: Boolean
)

/** 每小时衰减计算结果 */
data class DecayUpdate(
    val newEnergy: Int,
    val newValence: Float,
    val newArousal: Float
)
