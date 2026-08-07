package com.agent.ta.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agent.ta.data.model.AgentState
import com.agent.ta.data.model.DailySlot
import com.agent.ta.di.ServiceLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * StateMachine + LIGHT_SLEEP 集成测试（Phase 1 分级睡眠）
 *
 * 验证：
 * 1. LIGHT_SLEEP 状态 canReplyNow() 返回 true（可回复）
 * 2. UNAVAILABLE（深睡 slot）状态 canReplyNow() 返回 false
 * 3. computeCurrentState 正确派生（light slot → LIGHT_SLEEP、deep slot → UNAVAILABLE）
 * 4. getReplyDelaySec 在 LIGHT_SLEEP 状态返回 30-60 秒（迷糊慢回复）
 *
 * 运行环境：emulator（需要 ServiceLocator + Room DB 初始化）
 */
@RunWith(AndroidJUnit4::class)
class StateMachineSleepTest {

    private lateinit var stateMachine: StateMachine

    @Before
    fun setup() {
        // ServiceLocator 在 Application.onCreate 中初始化，androidTest 已完成此过程
        stateMachine = StateMachine()
    }

    @Test
    fun light_sleep_state_canReplyNow_returns_true() {
        // 通过 init 传入 sleepDepth="light" 的 slot 让状态机进入 LIGHT_SLEEP
        // 构造一个覆盖当前时间的浅睡 slot
        val now = java.time.LocalTime.now()
        val start = now.minusMinutes(30).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val end = now.plusMinutes(30).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val slot = DailySlot(
            start = start,
            end = end,
            state = "unavailable",
            activity = "入睡浅睡",
            sleepDepth = "light"
        )

        stateMachine.init(listOf(slot), emptyMap(), 1L)

        assertEquals(
            "浅睡 slot 应让状态机进入 LIGHT_SLEEP",
            AgentState.LIGHT_SLEEP,
            stateMachine.currentState.value
        )
        assertTrue("LIGHT_SLEEP 应可回复", stateMachine.canReplyNow())
    }

    @Test
    fun deep_sleep_slot_canReplyNow_returns_false() {
        val now = java.time.LocalTime.now()
        val start = now.minusMinutes(30).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val end = now.plusMinutes(30).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val slot = DailySlot(
            start = start,
            end = end,
            state = "unavailable",
            activity = "深睡",
            sleepDepth = "deep"
        )

        stateMachine.init(listOf(slot), emptyMap(), 1L)

        assertEquals(
            "深睡 slot 应让状态机进入 UNAVAILABLE",
            AgentState.UNAVAILABLE,
            stateMachine.currentState.value
        )
        assertFalse("UNAVAILABLE 不可回复", stateMachine.canReplyNow())
    }

    @Test
    fun null_sleepDepth_uses_slot_state_field() {
        val now = java.time.LocalTime.now()
        val start = now.minusMinutes(30).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val end = now.plusMinutes(30).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val slot = DailySlot(
            start = start,
            end = end,
            state = "busy",
            activity = "写代码",
            sleepDepth = null
        )

        stateMachine.init(listOf(slot), emptyMap(), 1L)

        assertEquals(
            "sleepDepth=null 应沿用 slot.state",
            AgentState.BUSY,
            stateMachine.currentState.value
        )
        assertTrue("BUSY 可回复", stateMachine.canReplyNow())
    }

    @Test
    fun light_sleep_replyDelay_returns_30_to_60_seconds() {
        val now = java.time.LocalTime.now()
        val start = now.minusMinutes(30).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val end = now.plusMinutes(30).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val slot = DailySlot(
            start = start,
            end = end,
            state = "unavailable",
            activity = "将醒浅睡",
            sleepDepth = "light"
        )

        stateMachine.init(listOf(slot), emptyMap(), 1L)

        val delay = stateMachine.getReplyDelaySec()
        assertNotNull("LIGHT_SLEEP 应有延迟配置", delay)
        if (delay != null) {
            assertTrue(
                "LIGHT_SLEEP 延迟应在 30-60 秒（迷糊慢回复），实际=$delay",
                delay in 30..60
            )
        }
    }

    @Test
    fun agentState_fromId_supports_light_sleep() {
        // 直接测试 AgentState.fromId 兼容性
        assertEquals(
            "light_sleep 应解析为 LIGHT_SLEEP",
            AgentState.LIGHT_SLEEP,
            AgentState.fromId("light_sleep")
        )
    }
}
