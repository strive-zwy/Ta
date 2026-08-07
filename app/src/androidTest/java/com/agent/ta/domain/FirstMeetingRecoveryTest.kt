package com.agent.ta.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agent.ta.data.default.DefaultAgent
import com.agent.ta.data.local.entity.AgentConfigEntity
import com.agent.ta.data.local.entity.FirstMeetingStateEntity
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.di.ServiceLocator
import com.agent.ta.domain.firstmeeting.FirstMeetingPhase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 首次见面进程恢复与失败回退测试（Task 17）
 *
 * 验证场景：
 * 1. 问候生成中进程重启，状态可恢复且不会重复两条
 * 2. 问候 LLM 失败后状态回退到 NOT_STARTED，允许下次重试
 * 3. TTS 失败仍保留文字问候并进入等待称呼状态
 * 4. 已完成的 Agent 不会再次触发问候
 *
 * 运行环境：emulator（需要 ServiceLocator + Room DB 初始化）
 */
@RunWith(AndroidJUnit4::class)
class FirstMeetingRecoveryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val agentConfigDao get() = ServiceLocator.agentConfigDao
    private val firstMeetingDao get() = ServiceLocator.firstMeetingStateDao
    private val coordinator get() = ServiceLocator.firstMeetingCoordinator

    @Before
    fun setup() = runBlocking {
        val db = ServiceLocator.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM agent_config")
        db.execSQL("DELETE FROM first_meeting_state")
        db.execSQL("DELETE FROM chat_messages")
    }

    @After
    fun tearDown() = runBlocking {
        val db = ServiceLocator.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM agent_config")
        db.execSQL("DELETE FROM first_meeting_state")
        db.execSQL("DELETE FROM chat_messages")
    }

    private suspend fun insertAgentWithPhase(phase: FirstMeetingPhase): Long {
        val config = DefaultAgent.create()
        val configJson = json.encodeToString(AgentConfig.serializer(), config)
        val agentId = agentConfigDao.insert(
            AgentConfigEntity(
                configJson = configJson,
                agentName = "测试Agent",
                importedAt = System.currentTimeMillis(),
                isActive = false
            )
        )
        firstMeetingDao.upsert(
            FirstMeetingStateEntity(agentId = agentId, phase = phase.id)
        )
        return agentId
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 1：问候生成中进程重启，状态可恢复且不会重复两条
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun greeting_in_progress_persists_across_restart_and_prevents_duplicate() = runBlocking {
        val agentId = insertAgentWithPhase(FirstMeetingPhase.GREETING_IN_PROGRESS)

        // 模拟进程重启：重新从 DB 读取状态
        val recoveredPhase = coordinator.getPhase(agentId)
        assertEquals(
            "进程重启后应恢复为 GREETING_IN_PROGRESS",
            FirstMeetingPhase.GREETING_IN_PROGRESS,
            recoveredPhase
        )

        // 重启后再次尝试 beginGreeting 应失败（CAS 抢占，状态不是 NOT_STARTED）
        val grabbed = coordinator.beginGreeting(agentId)
        assertFalse("GREETING_IN_PROGRESS 状态下 beginGreeting 应失败，防止重复问候", grabbed)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 2：问候 LLM 失败后状态回退到 NOT_STARTED，允许下次重试
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun greeting_llm_failure_resets_to_not_started_allows_retry() = runBlocking {
        val agentId = insertAgentWithPhase(FirstMeetingPhase.GREETING_IN_PROGRESS)

        // 模拟 LLM 失败
        coordinator.onGreetingLlmFailure(agentId)

        assertEquals(
            "LLM 失败后应回退到 NOT_STARTED",
            FirstMeetingPhase.NOT_STARTED,
            coordinator.getPhase(agentId)
        )

        // 下次可以重新抢占
        val grabbed = coordinator.beginGreeting(agentId)
        assertTrue("回退后应能重新抢占 beginGreeting", grabbed)
        assertEquals(
            "抢占后应为 GREETING_IN_PROGRESS",
            FirstMeetingPhase.GREETING_IN_PROGRESS,
            coordinator.getPhase(agentId)
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 3：TTS 失败仍保留文字问候并进入等待称呼状态
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun greeting_tts_failure_advances_to_waiting_nickname() = runBlocking {
        val agentId = insertAgentWithPhase(FirstMeetingPhase.GREETING_IN_PROGRESS)

        // 模拟 TTS 失败（文字已入库）
        val messageId = 100L
        val sentAt = System.currentTimeMillis()
        coordinator.onGreetingTtsFailure(agentId, messageId, sentAt)

        val phase = coordinator.getPhase(agentId)
        assertEquals(
            "TTS 失败后应进入 WAITING_NICKNAME（文字已入库）",
            FirstMeetingPhase.WAITING_NICKNAME,
            phase
        )

        // 验证 greetingMessageId 和 greetingSentAt 已保存
        val state = firstMeetingDao.getByAgentId(agentId)
        assertNotNull("状态记录应存在", state)
        assertEquals("greetingMessageId 应已保存", messageId, state!!.greetingMessageId)
        assertEquals("greetingSentAt 应已保存", sentAt, state.greetingSentAt)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 4：已完成的 Agent 不会再次触发问候
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun completed_agent_does_not_trigger_greeting_again() = runBlocking {
        // 已完成首次见面（有称呼）
        val agentId = insertAgentWithPhase(FirstMeetingPhase.COMPLETED_WITH_NICKNAME)

        // 尝试 beginGreeting 应失败
        val grabbed = coordinator.beginGreeting(agentId)
        assertFalse("已完成的 Agent 不应再次触发问候", grabbed)

        // 也验证 COMPLETED_WITHOUT_NICKNAME
        val agentId2 = insertAgentWithPhase(FirstMeetingPhase.COMPLETED_WITHOUT_NICKNAME)
        val grabbed2 = coordinator.beginGreeting(agentId2)
        assertFalse("COMPLETED_WITHOUT_NICKNAME 也不应再次触发问候", grabbed2)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 5：NOT_STARTED 状态可以正常抢占并生成问候
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun not_started_agent_can_begin_greeting() = runBlocking {
        val agentId = insertAgentWithPhase(FirstMeetingPhase.NOT_STARTED)

        val grabbed = coordinator.beginGreeting(agentId)
        assertTrue("NOT_STARTED 应能抢占", grabbed)
        assertEquals(
            "抢占后应为 GREETING_IN_PROGRESS",
            FirstMeetingPhase.GREETING_IN_PROGRESS,
            coordinator.getPhase(agentId)
        )

        // 问候成功后推进到 WAITING_NICKNAME
        val messageId = 200L
        val sentAt = System.currentTimeMillis()
        coordinator.onGreetingSuccess(agentId, messageId, sentAt)
        assertEquals(
            "问候成功后应为 WAITING_NICKNAME",
            FirstMeetingPhase.WAITING_NICKNAME,
            coordinator.getPhase(agentId)
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 6：WAITING_NICKNAME 状态下用户给出称呼后完成
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun waiting_nickname_user_provides_nickname_completes() = runBlocking {
        val agentId = insertAgentWithPhase(FirstMeetingPhase.WAITING_NICKNAME)

        coordinator.onNicknameCaptured(agentId, "阿哲")
        assertEquals(
            "提供称呼后应为 COMPLETED_WITH_NICKNAME",
            FirstMeetingPhase.COMPLETED_WITH_NICKNAME,
            coordinator.getPhase(agentId)
        )
        assertTrue("终态应标记为已完成", coordinator.getPhase(agentId)?.isCompleted == true)
    }
}
