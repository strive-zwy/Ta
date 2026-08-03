package com.agent.ta.domain

import com.agent.ta.data.model.DailySlot
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * 睡眠时段拆分器（Phase 1 分级睡眠核心）
 *
 * 把一段跨午夜的睡觉时段拆分为 3 段：
 * - 入睡浅睡（N1/N2 为主）
 * - 深睡（N3 为主，跨午夜）
 * - 将醒浅睡（REM 为主）
 *
 * 算法基础：
 * - 人类睡眠每 90 分钟一个周期（N1→N2→N3→REM）
 * - 前半夜深睡多、后半夜 REM 多
 * - 短睡眠时深睡补偿（占比 ↑）、长睡眠时 REM ↑
 *
 * 情境扰动：
 * - LLM 输出 stress/fatigue/mood 三维参数
 * - stress ↑ → 深睡 ↓（睡不踏实）
 * - fatigue ↑ → 入睡浅睡 ↓（太累直接深睡）
 * - mood < -0.3 → 深睡 ↓（情绪低落影响深睡）
 * - 扰动范围钳制 ±15%
 *
 * fallback：
 * - 总睡眠 < 3 小时或 > 14 小时 → 返回 null
 * - 校验失败（深睡 < 2h、入睡浅睡 > 1.5h、将醒浅睡 > 2h）→ 重试固定比例 20/55/25 无扰动
 * - 仍失败 → 返回 null（调用方保留原单段）
 */
class SleepPhaseSplitter {

    /**
     * 情境扰动参数
     *
     * @param stress 压力值 0-1（0=轻松、0.5=普通、1=极度压力）
     * @param fatigue 疲劳值 0-1（0=精神、0.5=普通、1=极度疲劳）
     * @param mood 心情 -1~1（-1=低落、0=平静、1=愉悦）
     */
    data class SleepContextPerturbation(
        val stress: Float = 0.3f,
        val fatigue: Float = 0.3f,
        val mood: Float = 0.0f
    )

    companion object {
        /** 睡眠周期长度（分钟） */
        private const val CYCLE_MINUTES = 90

        /** 最小总睡眠时长（分钟），低于此值不拆分 */
        private const val MIN_TOTAL_SLEEP_MIN = 180  // 3 小时

        /** 最大总睡眠时长（分钟），超过此值不拆分 */
        private const val MAX_TOTAL_SLEEP_MIN = 840  // 14 小时

        /** 扰动最大幅度（±15%） */
        private const val MAX_PERTURBATION = 0.15f

        /** 校验：深睡段最小时长（分钟） */
        private const val MIN_DEEP_MIN = 120  // 2 小时

        /** 校验：入睡浅睡最大时长（分钟） */
        private const val MAX_FALL_ASLEEP_MIN = 90  // 1.5 小时

        /** 校验：将醒浅睡最大时长（分钟） */
        private const val MAX_WAKE_UP_MIN = 180  // 3 小时（长睡眠 REM 可达 2-3h）

        /** fallback 固定比例（无扰动） */
        private const val FALLBACK_LIGHT_RATIO = 0.20f
        private const val FALLBACK_DEEP_RATIO = 0.55f
        private const val FALLBACK_WAKE_RATIO = 0.25f
    }

    /**
     * 拆分睡觉时段为 3 段
     *
     * @param sleepStart 入睡时间（如 23:00）
     * @param wakeTime 起床时间（如 07:30，次日）
     * @param perturbation 情境扰动参数
     * @return 3 段 DailySlot（已填充 sleepDepth），失败返回 null
     */
    fun split(
        sleepStart: LocalTime,
        wakeTime: LocalTime,
        perturbation: SleepContextPerturbation = SleepContextPerturbation()
    ): List<DailySlot>? {
        // 计算总睡眠分钟数（跨午夜）
        val totalMinutes = computeTotalMinutes(sleepStart, wakeTime)

        // fallback 1：总时长异常
        if (totalMinutes < MIN_TOTAL_SLEEP_MIN || totalMinutes > MAX_TOTAL_SLEEP_MIN) {
            return null
        }

        // 计算周期数（用于决定基础比例）
        val cycleCount = totalMinutes / CYCLE_MINUTES
        if (cycleCount < 2) {
            return null
        }

        // 计算各阶段分钟数
        val (fallAsleepMin, deepMin, wakeUpMin) = computePhaseMinutes(totalMinutes, cycleCount, perturbation)

        // 计算时间边界
        val fallAsleepEnd = sleepStart.plusMinutes(fallAsleepMin.toLong())
        val deepEnd = fallAsleepEnd.plusMinutes(deepMin.toLong())
        val wakeEnd = sleepStart.plusMinutes(totalMinutes.toLong())  // = wakeTime

        // 构造 3 段 slot
        val slots = listOf(
            DailySlot(
                start = formatTime(sleepStart),
                end = formatTime(fallAsleepEnd),
                state = "unavailable",
                activity = "入睡浅睡",
                sleepDepth = "light"
            ),
            DailySlot(
                start = formatTime(fallAsleepEnd),
                end = formatTime(deepEnd),
                state = "unavailable",
                activity = "深睡",
                sleepDepth = "deep"
            ),
            DailySlot(
                start = formatTime(deepEnd),
                end = formatTime(wakeEnd),
                state = "unavailable",
                activity = "将醒浅睡",
                sleepDepth = "light"
            )
        )

        // 校验
        if (validateSlots(fallAsleepMin, deepMin, wakeUpMin, totalMinutes)) {
            return slots
        }

        // fallback 2：校验失败，用固定比例无扰动重试
        val fbFallAsleep = (totalMinutes * FALLBACK_LIGHT_RATIO).toInt()
        val fbDeep = (totalMinutes * FALLBACK_DEEP_RATIO).toInt()
        val fbWake = totalMinutes - fbFallAsleep - fbDeep

        if (validateSlots(fbFallAsleep, fbDeep, fbWake, totalMinutes)) {
            val fbFallAsleepEnd = sleepStart.plusMinutes(fbFallAsleep.toLong())
            val fbDeepEnd = fbFallAsleepEnd.plusMinutes(fbDeep.toLong())
            return listOf(
                DailySlot(
                    start = formatTime(sleepStart),
                    end = formatTime(fbFallAsleepEnd),
                    state = "unavailable",
                    activity = "入睡浅睡",
                    sleepDepth = "light"
                ),
                DailySlot(
                    start = formatTime(fbFallAsleepEnd),
                    end = formatTime(fbDeepEnd),
                    state = "unavailable",
                    activity = "深睡",
                    sleepDepth = "deep"
                ),
                DailySlot(
                    start = formatTime(fbDeepEnd),
                    end = formatTime(wakeEnd),
                    state = "unavailable",
                    activity = "将醒浅睡",
                    sleepDepth = "light"
                )
            )
        }

        // fallback 3：仍失败返回 null
        return null
    }

    /**
     * 计算总睡眠分钟数（跨午夜）
     */
    private fun computeTotalMinutes(start: LocalTime, end: LocalTime): Int {
        val startMin = start.hour * 60 + start.minute
        val endMin = end.hour * 60 + end.minute
        return if (endMin > startMin) {
            endMin - startMin
        } else {
            (24 * 60 - startMin) + endMin
        }
    }

    /**
     * 计算各阶段分钟数
     *
     * 基础比例（基于周期数，符合生理学）：
     * - 入睡浅睡（N1+N2）：~10%（30-60 分钟，入睡困难者可延长）
     * - 短睡眠（≤4 周期）：深睡 70%、将醒浅睡 20%（深睡补偿）
     * - 长睡眠（≥5 周期）：深睡 60%、将醒浅睡 30%（REM ↑）
     *
     * 情境扰动（±15%）：
     * - stress ↑ → 深睡 ↓（转为浅睡）
     * - fatigue ↑ → 入睡浅睡 ↓（转为深睡）
     * - mood < -0.3 → 深睡 ↓（转为浅睡）
     */
    private fun computePhaseMinutes(
        totalMinutes: Int,
        cycleCount: Int,
        perturbation: SleepContextPerturbation
    ): Triple<Int, Int, Int> {
        // 基础比例
        val baseFallAsleepRatio = 0.10f
        val baseWakeUpRatio = if (cycleCount >= 5) 0.30f else 0.20f
        val baseDeepRatio = 1.0f - baseFallAsleepRatio - baseWakeUpRatio

        // 计算扰动系数（-1.0 ~ 1.0，正数=增加深睡，负数=减少深睡）
        val stressEffect = -(perturbation.stress - 0.5f) * 2  // stress > 0.5 时为负
        val fatigueEffect = (perturbation.fatigue - 0.5f) * 2  // fatigue > 0.5 时为正
        val moodEffect = if (perturbation.mood < -0.3f) perturbation.mood else 0.0f

        val perturbationFactor = (stressEffect + fatigueEffect + moodEffect)
            .coerceIn(-MAX_PERTURBATION, MAX_PERTURBATION)

        // 调整深睡比例
        var deepRatio = (baseDeepRatio + perturbationFactor).coerceIn(0.3f, 0.75f)
        var fallAsleepRatio = baseFallAsleepRatio
        var wakeUpRatio = 1.0f - deepRatio - fallAsleepRatio

        // 钳制将醒浅睡下限
        if (wakeUpRatio < 0.1f) {
            wakeUpRatio = 0.1f
            deepRatio = 1.0f - fallAsleepRatio - wakeUpRatio
        }

        val fallAsleepMin = (totalMinutes * fallAsleepRatio).roundToInt()
        val deepMin = (totalMinutes * deepRatio).roundToInt()
        val wakeUpMin = totalMinutes - fallAsleepMin - deepMin

        return Triple(fallAsleepMin, deepMin, wakeUpMin)
    }

    /**
     * 校验拆分结果合理性
     */
    private fun validateSlots(
        fallAsleepMin: Int,
        deepMin: Int,
        wakeUpMin: Int,
        totalMinutes: Int
    ): Boolean {
        // 入睡浅睡 ≤ 1.5 小时
        if (fallAsleepMin > MAX_FALL_ASLEEP_MIN) return false

        // 将醒浅睡 ≤ 2.5 小时
        if (wakeUpMin > MAX_WAKE_UP_MIN) return false

        // 深睡段 ≥ 2 小时（短睡眠 < 6h 时放宽到 1.5h）
        val minDeep = if (totalMinutes < 360) 90 else MIN_DEEP_MIN
        if (deepMin < minDeep) return false

        return true
    }

    /**
     * 格式化时间为 "HH:MM"
     *
     * LocalTime.plusMinutes 自动处理跨午夜（如 23:00 + 90 = 00:30）
     */
    private fun formatTime(time: LocalTime): String {
        return String.format("%02d:%02d", time.hour, time.minute)
    }
}
