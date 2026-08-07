package com.agent.ta.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agent.ta.data.default.DefaultAgent
import com.agent.ta.data.local.entity.AgentConfigEntity
import com.agent.ta.data.local.entity.ChatMessageEntity
import com.agent.ta.data.local.entity.FirstMeetingStateEntity
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.di.ServiceLocator
import com.agent.ta.domain.firstmeeting.FirstMeetingPhase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Agent 切换并发与隔离测试（Task 17）
 *
 * 验证场景：
 * 1. 连续导入两个同名 Agent，各自有独立的首次见面状态
 * 2. AgentConfigEditor.updateAgent 只更新目标 Agent，不污染其他 Agent
 * 3. 聊天消息按 agentId 隔离，切换 Agent 后互不可见
 * 4. 两个 Agent 各自独立的 beginGreeting 抢占
 *
 * 运行环境：emulator（需要 ServiceLocator + Room DB 初始化）
 */
@RunWith(AndroidJUnit4::class)
class AgentSwitchConcurrencyTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val agentConfigDao get() = ServiceLocator.agentConfigDao
    private val firstMeetingDao get() = ServiceLocator.firstMeetingStateDao
    private val chatDao get() = ServiceLocator.chatMessageDao
    private val coordinator get() = ServiceLocator.firstMeetingCoordinator
    private val editor get() = ServiceLocator.agentConfigEditor

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

    private suspend fun insertAgent(name: String = "同名Agent"): Long {
        val config = DefaultAgent.create().copy(
            agent = DefaultAgent.create().agent.copy(name = name)
        )
        val configJson = json.encodeToString(AgentConfig.serializer(), config)
        val agentId = agentConfigDao.insert(
            AgentConfigEntity(
                configJson = configJson,
                agentName = name,
                importedAt = System.currentTimeMillis(),
                isActive = false
            )
        )
        firstMeetingDao.upsert(
            FirstMeetingStateEntity(agentId = agentId, phase = FirstMeetingPhase.NOT_STARTED.id)
        )
        return agentId
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 1：连续导入两个同名 Agent，各自有独立的首次见面状态
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun two_same_name_agents_have_independent_first_meeting_states() = runBlocking {
        val id1 = insertAgent("同名")
        val id2 = insertAgent("同名")

        assertNotEquals("两个同名 Agent 应有不同 ID", id1, id2)

        // Agent 1 抢占问候
        val grabbed1 = coordinator.beginGreeting(id1)
        assertTrue("Agent 1 应能抢占", grabbed1)

        // Agent 2 仍为 NOT_STARTED，也能抢占
        val grabbed2 = coordinator.beginGreeting(id2)
        assertTrue("Agent 2 应能独立抢占", grabbed2)

        // 两个 Agent 的状态独立
        assertEquals(
            "Agent 1 应为 GREETING_IN_PROGRESS",
            FirstMeetingPhase.GREETING_IN_PROGRESS,
            coordinator.getPhase(id1)
        )
        assertEquals(
            "Agent 2 应为 GREETING_IN_PROGRESS",
            FirstMeetingPhase.GREETING_IN_PROGRESS,
            coordinator.getPhase(id2)
        )

        // Agent 1 完成问候，Agent 2 不受影响
        coordinator.onGreetingSuccess(id1, 100L, System.currentTimeMillis())
        assertEquals(
            "Agent 1 应为 WAITING_NICKNAME",
            FirstMeetingPhase.WAITING_NICKNAME,
            coordinator.getPhase(id1)
        )
        assertEquals(
            "Agent 2 应仍为 GREETING_IN_PROGRESS",
            FirstMeetingPhase.GREETING_IN_PROGRESS,
            coordinator.getPhase(id2)
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 2：AgentConfigEditor.updateAgent 只更新目标 Agent
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun updateAgent_only_updates_target_agent_config() = runBlocking {
        val id1 = insertAgent("AgentA")
        val id2 = insertAgent("AgentB")

        // 只给 Agent 1 写入称呼
        editor.updateAgent(id1) { config ->
            config.copy(
                agent = config.agent.copy(
                    persona = config.agent.persona.copy(nicknameForUser = "阿哲")
                )
            )
        }

        // 读取两个 Agent 的配置
        val entity1 = agentConfigDao.getById(id1)!!
        val config1 = json.decodeFromString<AgentConfig>(entity1.configJson)
        assertEquals("Agent 1 称呼应为阿哲", "阿哲", config1.agent.persona.nicknameForUser)

        val entity2 = agentConfigDao.getById(id2)!!
        val config2 = json.decodeFromString<AgentConfig>(entity2.configJson)
        assertEquals("Agent 2 称呼应保持空", "", config2.agent.persona.nicknameForUser)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 3：聊天消息按 agentId 隔离
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun chat_messages_are_isolated_by_agent_id() = runBlocking {
        val id1 = insertAgent("AgentA")
        val id2 = insertAgent("AgentB")

        val now = System.currentTimeMillis()

        // 给 Agent 1 插入消息
        chatDao.insert(
            ChatMessageEntity(
                agentId = id1,
                direction = "inbound",
                text = "你好AgentA",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "received",
                createdAt = now
            )
        )
        chatDao.insert(
            ChatMessageEntity(
                agentId = id1,
                direction = "outbound",
                text = "嗨～我是AgentA",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "sent",
                createdAt = now + 1000
            )
        )

        // 给 Agent 2 插入消息
        chatDao.insert(
            ChatMessageEntity(
                agentId = id2,
                direction = "inbound",
                text = "你好AgentB",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "received",
                createdAt = now
            )
        )

        // 验证隔离
        val messages1 = chatDao.getAll(id1)
        val messages2 = chatDao.getAll(id2)

        assertEquals("Agent 1 应有 2 条消息", 2, messages1.size)
        assertEquals("Agent 2 应有 1 条消息", 1, messages2.size)
        assertTrue("Agent 1 消息应包含 AgentA 内容", messages1.any { it.text?.contains("AgentA") == true })
        assertTrue("Agent 2 消息应包含 AgentB 内容", messages2.any { it.text?.contains("AgentB") == true })
        assertTrue("Agent 1 不应看到 Agent 2 的消息", messages1.none { it.text?.contains("AgentB") == true })
        assertTrue("Agent 2 不应看到 Agent 1 的消息", messages2.none { it.text?.contains("AgentA") == true })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 4：两个 Agent 各自独立的 beginGreeting 抢占
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun concurrent_beginGreeting_for_different_agents_both_succeed() = runBlocking {
        val id1 = insertAgent("AgentA")
        val id2 = insertAgent("AgentB")

        // 模拟并发：两个 Agent 同时 beginGreeting
        val grabbed1 = coordinator.beginGreeting(id1)
        val grabbed2 = coordinator.beginGreeting(id2)

        // 两个都应成功，因为 CAS 是按 agentId 隔离的
        assertTrue("Agent 1 beginGreeting 应成功", grabbed1)
        assertTrue("Agent 2 beginGreeting 应成功", grabbed2)

        // 两个都推进到 GREETING_IN_PROGRESS
        assertEquals(FirstMeetingPhase.GREETING_IN_PROGRESS, coordinator.getPhase(id1))
        assertEquals(FirstMeetingPhase.GREETING_IN_PROGRESS, coordinator.getPhase(id2))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 5：同一 Agent 的并发 beginGreeting 只有一个成功
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun concurrent_beginGreeting_for_same_agent_only_one_succeeds() = runBlocking {
        val id = insertAgent("AgentA")

        // 第一次抢占成功
        val grabbed1 = coordinator.beginGreeting(id)
        assertTrue("第一次 beginGreeting 应成功", grabbed1)

        // 第二次抢占失败（状态已变为 GREETING_IN_PROGRESS）
        val grabbed2 = coordinator.beginGreeting(id)
        assertTrue("第二次 beginGreeting 应失败", !grabbed2)

        assertEquals(
            "状态应仍为 GREETING_IN_PROGRESS",
            FirstMeetingPhase.GREETING_IN_PROGRESS,
            coordinator.getPhase(id)
        )
    }
}
