package com.agent.ta.domain

import com.agent.ta.data.local.entity.DailyStateEntity
import com.agent.ta.data.local.entity.EmotionalStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EmotionalEngine 单元测试（Phase 3 情感势能驱动主动发起）
 *
 * 验证四个核心计算函数：
 * 1. applyTurnEnd — 对话轮驱动（valence 漂移 / arousal 推高 / 势能增量）
 * 2. applySilentAccumulation — 静默积累（心情驱动型系数）
 * 3. applyHourlyDecay — 每小时衰减（势能 -2 + valence/arousal 漂移）
 * 4. applySleepBaseline — 睡眠基线（昨日睡眠情况 → 今天起始情绪）
 */
class EmotionalEngineTest {

    private fun baseState(
        valence: Float = 0f,
        arousal: Float = 0.3f,
        potentialEnergy: Int = 0,
        lastUserInteractionAt: Long = System.currentTimeMillis()
    ) = EmotionalStateEntity(
        id = 1,
        valence = valence,
        arousal = arousal,
        potentialEnergy = potentialEnergy,
        lastEmotion = null,
        lastUserInteractionAt = lastUserInteractionAt,
        lastDecayAt = System.currentTimeMillis()
    )

    // ════ applyTurnEnd ════

    @Test
    fun applyTurnEnd_positive_intensity_increases_valence() {
        val current = baseState(valence = 0f)
        val update = EmotionalEngine.applyTurnEnd(
            intensity = 2.0f,
            emotion = "happy",
            current = current
        )
        assertTrue("正向强度应让 valence 上升", update.newValence > 0f)
        // valence = 0*0.7 + 1*0.3 = 0.3
        assertEquals(0.3f, update.newValence, 0.01f)
    }

    @Test
    fun applyTurnEnd_negative_intensity_decreases_valence() {
        val current = baseState(valence = 0f)
        val update = EmotionalEngine.applyTurnEnd(
            intensity = -2.0f,
            emotion = "sad",
            current = current
        )
        assertTrue("负向强度应让 valence 下降", update.newValence < 0f)
        // valence = 0*0.7 + (-1)*0.3 = -0.3
        assertEquals(-0.3f, update.newValence, 0.01f)
    }

    @Test
    fun applyTurnEnd_high_arousal_clamped_to_1() {
        val current = baseState(arousal = 0.9f)
        val update = EmotionalEngine.applyTurnEnd(
            intensity = 2.0f,
            emotion = "excited",
            current = current
        )
        // arousal = 0.9 + 2*0.2 = 1.3 → clamp 到 1.0
        assertEquals(1.0f, update.newArousal, 0.001f)
    }

    @Test
    fun applyTurnEnd_energy_increment_proportional_to_intensity() {
        val current = baseState()
        val update = EmotionalEngine.applyTurnEnd(
            intensity = 2.0f,
            emotion = "excited",
            current = current
        )
        // energyIncrement = |2.0| * 8 = 16
        assertEquals(16, update.energyIncrement)
    }

    // ════ applySilentAccumulation ════

    @Test
    fun applySilentAccumulation_happy_valence_gets_1_5x_increment() {
        val now = System.currentTimeMillis()
        // 1 小时前用户最后互动
        val current = baseState(valence = 0.6f, lastUserInteractionAt = now - 3600_000L)
        val update = EmotionalEngine.applySilentAccumulation(current, now)
        // valence > 0.5 → +7
        assertEquals(7, update.energyDelta)
        assertEquals(false, update.isDecay)
    }

    @Test
    fun applySilentAccumulation_sad_valence_silent_over_4h_decreases_energy() {
        val now = System.currentTimeMillis()
        // 5 小时前用户最后互动
        val current = baseState(valence = -0.6f, lastUserInteractionAt = now - 5 * 3600_000L)
        val update = EmotionalEngine.applySilentAccumulation(current, now)
        // valence < -0.5 且静默 > 4h → -5
        assertEquals(-5, update.energyDelta)
        assertTrue("应标记为衰减模式", update.isDecay)
    }

    @Test
    fun applySilentAccumulation_sad_valence_silent_under_4h_still_increases() {
        val now = System.currentTimeMillis()
        // 2 小时前用户最后互动
        val current = baseState(valence = -0.6f, lastUserInteractionAt = now - 2 * 3600_000L)
        val update = EmotionalEngine.applySilentAccumulation(current, now)
        // valence < -0.5 但静默 < 4h → 仍 +2（给用户哄人窗口）
        assertEquals(2, update.energyDelta)
        assertEquals(false, update.isDecay)
    }

    // ════ applyHourlyDecay ════

    @Test
    fun applyHourlyDecay_reduces_energy_and_drifts_to_neutral() {
        val current = baseState(valence = 0.5f, arousal = 0.6f, potentialEnergy = 50)
        val update = EmotionalEngine.applyHourlyDecay(current)
        // 势能 -2
        assertEquals(48, update.newEnergy)
        // valence 向 0 漂移 0.05
        assertTrue("valence 应向 0 漂移", update.newValence < 0.5f)
        assertEquals(0.45f, update.newValence, 0.01f)
        // arousal 向 0.3 漂移 0.03
        assertTrue("arousal 应向 0.3 漂移", update.newArousal < 0.6f)
        assertEquals(0.57f, update.newArousal, 0.01f)
    }

    @Test
    fun applyHourlyDecay_clamps_energy_to_zero() {
        val current = baseState(potentialEnergy = 1)
        val update = EmotionalEngine.applyHourlyDecay(current)
        // 1 - 2 = -1 → clamp 到 0
        assertEquals(0, update.newEnergy)
    }

    // ════ applySleepBaseline ════

    @Test
    fun applySleepBaseline_short_sleep_makes_agent_irritable() {
        val current = baseState(valence = 0f, arousal = 0.3f)
        val yesterday = DailyStateEntity(
            date = "2026-07-29",
            sleepTime = "02:00",
            wakeTime = "07:00",
            sleepDurationMin = 300,  // 5 小时 < 360
            mood = 0f,
            fatigue = 0.5f,
            stress = 0.3f,
            energy = 0.4f,
            mainActivities = "[]",
            specialEvents = "[]",
            hadInteractionWithUser = true,
            interactionCount = 10,
            summary = ""
        )
        val newState = EmotionalEngine.applySleepBaseline(yesterday, current)
        // 睡不够 → valence -0.3, arousal 0.5
        assertEquals(-0.3f, newState.valence, 0.01f)
        assertEquals(0.5f, newState.arousal, 0.01f)
    }

    @Test
    fun applySleepBaseline_normal_sleep_starts_neutral() {
        val current = baseState(valence = 0.8f, arousal = 0.9f)  // 故意非中性
        val yesterday = DailyStateEntity(
            date = "2026-07-29",
            sleepTime = "23:00",
            wakeTime = "07:00",
            sleepDurationMin = 480,  // 8 小时 ≥ 360
            mood = 0.5f,
            fatigue = 0.3f,
            stress = 0.2f,
            energy = 0.8f,
            mainActivities = "[]",
            specialEvents = "[]",
            hadInteractionWithUser = true,
            interactionCount = 15,
            summary = ""
        )
        val newState = EmotionalEngine.applySleepBaseline(yesterday, current)
        // 睡够且疲劳不高 → valence=0, arousal=0.3
        assertEquals(0f, newState.valence, 0.01f)
        assertEquals(0.3f, newState.arousal, 0.01f)
    }

    @Test
    fun applySleepBaseline_null_yesterday_returns_neutral() {
        val current = baseState(valence = 0.8f, arousal = 0.9f)
        val newState = EmotionalEngine.applySleepBaseline(null, current)
        // 无昨日数据 → 中性默认值
        assertEquals(0f, newState.valence, 0.01f)
        assertEquals(0.3f, newState.arousal, 0.01f)
    }
}
