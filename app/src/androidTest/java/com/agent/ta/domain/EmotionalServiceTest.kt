package com.agent.ta.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * EmotionalService 集成测试（Phase 3 情感势能驱动主动发起 Step 13）
 *
 * 验证：
 * 1. getCurrentState 首次调用自动初始化为中性情绪（valence=0, arousal=0.3, potentialEnergy=0）
 * 2. onTurnCompleted 推进 valence/arousal/势能
 * 3. onUserMessageReceived 重置静默计时
 * 4. consumeEnergy 减少势能
 *
 * 运行环境：emulator（需要 ServiceLocator + Room DB 初始化）
 */
@RunWith(AndroidJUnit4::class)
class EmotionalServiceTest {

    private lateinit var service: EmotionalService

    @Before
    fun setup() {
        // ServiceLocator 在 Application.onCreate 中初始化，androidTest 已完成此过程
        // 清理 emotional_state 表，确保每个测试从干净状态开始
        val db = ServiceLocator.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM emotional_state")
        service = EmotionalService()
    }

    @Test
    fun getCurrentState_first_call_initializes_neutral_state() = runBlocking {
        val state = service.getCurrentState()

        assertEquals("首次调用应初始化为中性 valence", 0f, state.valence, 0.01f)
        assertEquals("首次调用应初始化为基线 arousal 0.3", 0.3f, state.arousal, 0.01f)
        assertEquals("初始 potentialEnergy 应为 0", 0, state.potentialEnergy)
    }

    @Test
    fun onTurnCompleted_updates_valence_and_energy() = runBlocking {
        // 先确保初始化
        val before = service.getCurrentState()
        assertEquals("前置：初始 valence 应为 0", 0f, before.valence, 0.01f)
        assertEquals("前置：初始 potentialEnergy 应为 0", 0, before.potentialEnergy)

        // intensity=2.0（强烈兴奋）, emotion=happy
        // 期望：valence 上升（0 → 0.3）, potentialEnergy 增加 16
        service.onTurnCompleted(emotionIntensity = 2.0f, emotion = "happy")

        val after = service.getCurrentState()
        assertTrue("onTurnCompleted 后 valence 应上升", after.valence > 0f)
        assertEquals("onTurnCompleted 后 valence 应为 0.3", 0.3f, after.valence, 0.01f)
        assertTrue("onTurnCompleted 后 potentialEnergy 应增加", after.potentialEnergy > 0)
        assertEquals("potentialEnergy 应为 16", 16, after.potentialEnergy)
    }

    @Test
    fun onUserMessageReceived_resets_silent_timer() = runBlocking {
        // 先确保初始化，记录初始 lastUserInteractionAt
        val initial = service.getCurrentState()
        val initialTs = initial.lastUserInteractionAt

        // 等待一小段时间确保时间戳不同
        Thread.sleep(50)

        service.onUserMessageReceived()

        val after = service.getCurrentState()
        assertTrue(
            "onUserMessageReceived 后 lastUserInteractionAt 应更新",
            after.lastUserInteractionAt > initialTs
        )
    }

    @Test
    fun consumeEnergy_reduces_potential_energy() = runBlocking {
        // 先通过 onTurnCompleted 让势能 > 0
        service.onTurnCompleted(emotionIntensity = 2.0f, emotion = "happy")
        val before = service.getCurrentState()
        assertTrue("前置：势能应 > 0", before.potentialEnergy > 0)
        val energyBefore = before.potentialEnergy

        // 消耗 30
        service.consumeEnergy(30)

        val after = service.getCurrentState()
        assertEquals(
            "consumeEnergy(30) 后势能应减少 30",
            energyBefore - 30,
            after.potentialEnergy
        )
    }
}
