package com.agent.ta.domain.firstmeeting

import com.agent.ta.data.local.dao.FirstMeetingStateDao
import com.agent.ta.data.local.entity.FirstMeetingStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FirstMeetingCoordinator 纯状态机单元测试（Task 11）
 *
 * 覆盖全部合法流转和非法重复触发。
 * 使用内存 mock DAO 验证状态机逻辑，不依赖 Room/Android。
 */
class FirstMeetingCoordinatorTest {

    private lateinit var mockDao: MockFirstMeetingStateDao
    private lateinit var coordinator: FirstMeetingCoordinator

    @Before
    fun setup() {
        mockDao = MockFirstMeetingStateDao()
        coordinator = FirstMeetingCoordinator(mockDao)
    }

    // ===== beginGreeting 抢占 =====

    @Test
    fun beginGreeting_from_not_started_succeeds() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.NOT_STARTED)
        val result = coordinator.beginGreeting(1L)
        assertTrue("NOT_STARTED → GREETING_IN_PROGRESS 应成功", result)
        assertEquals(FirstMeetingPhase.GREETING_IN_PROGRESS, mockDao.getPhase(1L))
    }

    @Test
    fun beginGreeting_from_greeting_in_progress_fails() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.GREETING_IN_PROGRESS)
        val result = coordinator.beginGreeting(1L)
        assertFalse("已在进行中时不应重复触发", result)
        assertEquals(FirstMeetingPhase.GREETING_IN_PROGRESS, mockDao.getPhase(1L))
    }

    @Test
    fun beginGreeting_from_waiting_nickname_fails() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.WAITING_NICKNAME)
        val result = coordinator.beginGreeting(1L)
        assertFalse("已等待称呼时不应重新开始问候", result)
        assertEquals(FirstMeetingPhase.WAITING_NICKNAME, mockDao.getPhase(1L))
    }

    @Test
    fun beginGreeting_from_completed_fails() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.COMPLETED_WITH_NICKNAME)
        val result = coordinator.beginGreeting(1L)
        assertFalse("已完成时不应重新开始", result)
    }

    @Test
    fun beginGreeting_concurrent_calls_only_one_succeeds() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.NOT_STARTED)
        // 模拟并发：第一次成功后状态已变，第二次应失败
        val first = coordinator.beginGreeting(1L)
        val second = coordinator.beginGreeting(1L)
        assertTrue("第一次抢占应成功", first)
        assertFalse("第二次抢占应失败", second)
    }

    // ===== 问候成功：进入 WAITING_NICKNAME =====

    @Test
    fun onGreetingSuccess_saves_message_id_and_enters_waiting_nickname() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.GREETING_IN_PROGRESS)
        val now = System.currentTimeMillis()
        coordinator.onGreetingSuccess(1L, messageId = 42L, sentAt = now)
        val state = mockDao.getByAgentId(1L)
        assertEquals(FirstMeetingPhase.WAITING_NICKNAME.id, state?.phase)
        assertEquals(42L, state?.greetingMessageId)
        assertEquals(now, state?.greetingSentAt)
    }

    @Test
    fun onGreetingSuccess_from_not_started_throws() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.NOT_STARTED)
        try {
            coordinator.onGreetingSuccess(1L, 1L, System.currentTimeMillis())
            assert(false) { "应抛异常：状态不匹配" }
        } catch (e: IllegalStateException) {
            // 预期
        }
    }

    // ===== LLM 失败：回退到 NOT_STARTED =====

    @Test
    fun onGreetingLlmFailure_reverts_to_not_started() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.GREETING_IN_PROGRESS)
        coordinator.onGreetingLlmFailure(1L)
        assertEquals(FirstMeetingPhase.NOT_STARTED, mockDao.getPhase(1L))
    }

    // ===== TTS 失败：不回退（文字已成功）=====

    @Test
    fun onGreetingTtsFailure_does_not_revert() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.GREETING_IN_PROGRESS)
        coordinator.onGreetingTtsFailure(1L, messageId = 99L, sentAt = 1000L)
        // TTS 失败后仍应保存 greetingMessageId 并进入 WAITING_NICKNAME
        val state = mockDao.getByAgentId(1L)
        assertEquals(FirstMeetingPhase.WAITING_NICKNAME.id, state?.phase)
        assertEquals(99L, state?.greetingMessageId)
    }

    // ===== 用户给出明确称呼：完成 =====

    @Test
    fun onNicknameCaptured_from_waiting_nickname_completes_with_nickname() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.WAITING_NICKNAME)
        coordinator.onNicknameCaptured(1L, "阿哲")
        assertEquals(FirstMeetingPhase.COMPLETED_WITH_NICKNAME, mockDao.getPhase(1L))
        assertTrue(mockDao.getPhase(1L)?.isCompleted == true)
    }

    @Test
    fun onNicknameCaptured_from_follow_up_asked_completes_with_nickname() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.FOLLOW_UP_ASKED)
        coordinator.onNicknameCaptured(1L, "明哥")
        assertEquals(FirstMeetingPhase.COMPLETED_WITH_NICKNAME, mockDao.getPhase(1L))
    }

    // ===== 第一次未识别：进入 FOLLOW_UP_ASKED =====

    @Test
    fun onNicknameUnrecognized_from_waiting_nickname_enters_follow_up() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.WAITING_NICKNAME)
        coordinator.onNicknameUnrecognized(1L)
        assertEquals(FirstMeetingPhase.FOLLOW_UP_ASKED, mockDao.getPhase(1L))
        assertTrue(mockDao.getPhase(1L)?.isAwaitingNickname == true)
    }

    // ===== 第二次仍未识别：完成但不带称呼 =====

    @Test
    fun onNicknameUnrecognized_from_follow_up_asked_completes_without_nickname() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.FOLLOW_UP_ASKED)
        coordinator.onNicknameUnrecognized(1L)
        assertEquals(FirstMeetingPhase.COMPLETED_WITHOUT_NICKNAME, mockDao.getPhase(1L))
    }

    // ===== 用户明确拒绝：立即结束 =====

    @Test
    fun onUserDeclined_from_waiting_nickname_completes_without_nickname() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.WAITING_NICKNAME)
        coordinator.onUserDeclined(1L)
        assertEquals(FirstMeetingPhase.COMPLETED_WITHOUT_NICKNAME, mockDao.getPhase(1L))
    }

    @Test
    fun onUserDeclined_from_follow_up_asked_completes_without_nickname() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.FOLLOW_UP_ASKED)
        coordinator.onUserDeclined(1L)
        assertEquals(FirstMeetingPhase.COMPLETED_WITHOUT_NICKNAME, mockDao.getPhase(1L))
    }

    // ===== 终态不可流转 =====

    @Test
    fun onNicknameCaptured_from_completed_throws() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.COMPLETED_WITH_NICKNAME)
        try {
            coordinator.onNicknameCaptured(1L, "新称呼")
            assert(false) { "应抛异常：已完成" }
        } catch (e: IllegalStateException) {
            // 预期
        }
    }

    @Test
    fun onNicknameUnrecognized_from_completed_throws() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.COMPLETED_WITHOUT_NICKNAME)
        try {
            coordinator.onNicknameUnrecognized(1L)
            assert(false) { "应抛异常：已完成" }
        } catch (e: IllegalStateException) {
            // 预期
        }
    }

    // ===== 多 Agent 隔离 =====

    @Test
    fun different_agents_have_independent_phases() = runBlocking {
        mockDao.setPhase(1L, FirstMeetingPhase.NOT_STARTED)
        mockDao.setPhase(2L, FirstMeetingPhase.WAITING_NICKNAME)

        coordinator.beginGreeting(1L)
        assertEquals(FirstMeetingPhase.GREETING_IN_PROGRESS, mockDao.getPhase(1L))
        assertEquals(FirstMeetingPhase.WAITING_NICKNAME, mockDao.getPhase(2L))
    }

    // ===== Mock DAO =====

    private class MockFirstMeetingStateDao : FirstMeetingStateDao {
        private val store = mutableMapOf<Long, FirstMeetingStateEntity>()

        fun setPhase(agentId: Long, phase: FirstMeetingPhase) {
            store[agentId] = FirstMeetingStateEntity(
                agentId = agentId,
                phase = phase.id,
                updatedAt = System.currentTimeMillis()
            )
        }

        fun getPhase(agentId: Long): FirstMeetingPhase? =
            store[agentId]?.phase?.let { FirstMeetingPhase.fromId(it) }

        override suspend fun upsert(entity: FirstMeetingStateEntity) {
            store[entity.agentId] = entity
        }

        override suspend fun getByAgentId(agentId: Long): FirstMeetingStateEntity? = store[agentId]

        override fun observeByAgentId(agentId: Long): kotlinx.coroutines.flow.Flow<FirstMeetingStateEntity?> =
            kotlinx.coroutines.flow.flowOf(store[agentId])

        override suspend fun updatePhaseIf(
            agentId: Long,
            fromPhase: String,
            toPhase: String,
            updatedAt: Long
        ): Int {
            val current = store[agentId] ?: return 0
            if (current.phase != fromPhase) return 0
            store[agentId] = current.copy(phase = toPhase, updatedAt = updatedAt)
            return 1
        }

        override suspend fun updatePhase(agentId: Long, phase: String, updatedAt: Long) {
            val current = store[agentId] ?: return
            store[agentId] = current.copy(phase = phase, updatedAt = updatedAt)
        }

        override suspend fun updateGreeting(
            agentId: Long,
            messageId: Long,
            sentAt: Long,
            phase: String,
            updatedAt: Long
        ) {
            val current = store[agentId] ?: return
            store[agentId] = current.copy(
                greetingMessageId = messageId,
                greetingSentAt = sentAt,
                phase = phase,
                updatedAt = updatedAt
            )
        }

        override suspend fun deleteByAgentId(agentId: Long) {
            store.remove(agentId)
        }
    }
}
