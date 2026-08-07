package com.agent.ta.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agent.ta.data.local.entity.AgentConfigEntity
import com.agent.ta.data.local.entity.ChatMessageEntity
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.data.default.DefaultAgent
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Agent 切换聊天数据源测试（Task 10）
 *
 * 验证：
 * 1. 切换 active agent 后，observeAll 立即返回目标 Agent 的消息
 * 2. 旧 Agent 的消息不可见
 * 3. 切回后恢复旧 Agent 的消息
 * 4. 导入新 Agent 后，新 Agent 的会话为空
 *
 * 运行环境：emulator（需要 ServiceLocator + Room DB 初始化）
 */
@RunWith(AndroidJUnit4::class)
class AgentSwitchChatTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val agentConfigDao get() = ServiceLocator.agentConfigDao
    private val chatDao get() = ServiceLocator.chatMessageDao
    private val activeAgentManager get() = ServiceLocator.activeAgentManager

    private var agentAId: Long = 0L
    private var agentBId: Long = 0L

    @Before
    fun setup() = runBlocking {
        // 清空表
        val db = ServiceLocator.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM agent_config")
        db.execSQL("DELETE FROM chat_messages")

        // 插入两个 Agent 配置
        val configA = DefaultAgent.create().copy(
            agent = DefaultAgent.create().agent.copy(name = "AgentA")
        )
        val configB = DefaultAgent.create().copy(
            agent = DefaultAgent.create().agent.copy(name = "AgentB")
        )

        agentAId = agentConfigDao.insert(
            AgentConfigEntity(
                configJson = json.encodeToString(AgentConfig.serializer(), configA),
                agentName = "AgentA",
                importedAt = 1000L,
                isActive = true
            )
        )
        agentBId = agentConfigDao.insert(
            AgentConfigEntity(
                configJson = json.encodeToString(AgentConfig.serializer(), configB),
                agentName = "AgentB",
                importedAt = 2000L,
                isActive = false
            )
        )

        // 初始化 activeAgentManager
        activeAgentManager.ensureDefaultAgentPersisted()

        // 为两个 Agent 各插入几条消息
        val now = System.currentTimeMillis()
        chatDao.insert(
            ChatMessageEntity(
                agentId = agentAId,
                direction = "inbound",
                text = "A 的第一条消息",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "received",
                createdAt = now
            )
        )
        chatDao.insert(
            ChatMessageEntity(
                agentId = agentAId,
                direction = "outbound",
                text = "A 的回复",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "sent",
                createdAt = now + 1
            )
        )
        chatDao.insert(
            ChatMessageEntity(
                agentId = agentBId,
                direction = "inbound",
                text = "B 的第一条消息",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "received",
                createdAt = now
            )
        )
    }

    @After
    fun tearDown() = runBlocking {
        val db = ServiceLocator.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM agent_config")
        db.execSQL("DELETE FROM chat_messages")
    }

    @Test
    fun switch_agent_switches_chat_list_immediately() = runBlocking {
        // 初始：Agent A 激活，observeAll(agentAId) 返回 A 的消息
        activeAgentManager.switchTo(agentAId)
        val aMessages = chatDao.observeAll(agentAId).first()
        assertEquals("Agent A 应有 2 条消息", 2, aMessages.size)
        assertTrue("Agent A 的消息内容正确", aMessages.all { it.text?.contains("A 的") == true })

        // 切换到 Agent B
        activeAgentManager.switchTo(agentBId)
        val bMessages = chatDao.observeAll(agentBId).first()
        assertEquals("Agent B 应有 1 条消息", 1, bMessages.size)
        assertEquals("B 的第一条消息", bMessages[0].text)
        assertTrue("Agent A 的消息在 B 视图下不可见", bMessages.none { it.text?.contains("A 的") == true })

        // 切回 Agent A
        activeAgentManager.switchTo(agentAId)
        val aMessagesAgain = chatDao.observeAll(agentAId).first()
        assertEquals("切回后 Agent A 仍有 2 条消息", 2, aMessagesAgain.size)
        assertTrue("切回后 Agent A 的消息恢复", aMessagesAgain.all { it.text?.contains("A 的") == true })
    }

    @Test
    fun active_agent_id_flow_emits_correct_id_after_switch() = runBlocking {
        // 初始激活 A
        activeAgentManager.switchTo(agentAId)
        assertEquals(agentAId, activeAgentManager.activeAgentId.value)

        // 切换到 B
        activeAgentManager.switchTo(agentBId)
        assertEquals(agentBId, activeAgentManager.activeAgentId.value)

        // 切回 A
        activeAgentManager.switchTo(agentAId)
        assertEquals(agentAId, activeAgentManager.activeAgentId.value)
    }

    @Test
    fun new_agent_has_empty_conversation() = runBlocking {
        // 插入一个全新的 Agent C
        val configC = DefaultAgent.create().copy(
            agent = DefaultAgent.create().agent.copy(name = "AgentC")
        )
        val agentCId = agentConfigDao.insert(
            AgentConfigEntity(
                configJson = json.encodeToString(AgentConfig.serializer(), configC),
                agentName = "AgentC",
                importedAt = 3000L,
                isActive = false
            )
        )

        // 切换到 C
        activeAgentManager.switchTo(agentCId)

        // C 的会话应为空
        val cMessages = chatDao.observeAll(agentCId).first()
        assertEquals("新 Agent C 的会话应为空", 0, cMessages.size)

        // A 和 B 的消息数量不受影响
        assertEquals("Agent A 仍有 2 条消息", 2, chatDao.getAll(agentAId).size)
        assertEquals("Agent B 仍有 1 条消息", 1, chatDao.getAll(agentBId).size)
    }
}
