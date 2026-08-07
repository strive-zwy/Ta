package com.agent.ta.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agent.ta.data.local.entity.ChatMessageEntity
import com.agent.ta.data.local.entity.ConversationSummaryEntity
import com.agent.ta.data.local.entity.MemoryEntity
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Agent 隔离对话数据测试（Task 5）
 *
 * 验证：
 * 1. Agent A/B 插入消息后，各自查询只能看到自己的消息
 * 2. Agent A/B 插入记忆后，各自查询只能看到自己的记忆
 * 3. Agent A/B 插入摘要后，各自查询只能看到自己的摘要
 * 4. updateStatus/deleteAll 等 mutation 也按 agentId 隔离
 *
 * 运行环境：emulator（需要 ServiceLocator + Room DB 初始化）
 */
@RunWith(AndroidJUnit4::class)
class AgentScopedConversationTest {

    private val agentA = 1001L
    private val agentB = 1002L

    private val chatDao get() = ServiceLocator.chatMessageDao
    private val memoryDao get() = ServiceLocator.memoryDao
    private val summaryDao get() = ServiceLocator.conversationSummaryDao

    @Before
    fun setup() = runBlocking {
        // 清空三张表，确保每个测试从干净状态开始
        val db = ServiceLocator.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM chat_messages")
        db.execSQL("DELETE FROM memories")
        db.execSQL("DELETE FROM conversation_summaries")
    }

    @After
    fun tearDown() = runBlocking {
        val db = ServiceLocator.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM chat_messages WHERE agentId IN ($agentA, $agentB)")
        db.execSQL("DELETE FROM memories WHERE agentId IN ($agentA, $agentB)")
        db.execSQL("DELETE FROM conversation_summaries WHERE agentId IN ($agentA, $agentB)")
    }

    @Test
    fun chat_messages_isolated_per_agent() = runBlocking {
        val now = System.currentTimeMillis()
        chatDao.insert(
            ChatMessageEntity(
                agentId = agentA,
                direction = "inbound",
                text = "A 的消息",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "received",
                createdAt = now
            )
        )
        chatDao.insert(
            ChatMessageEntity(
                agentId = agentB,
                direction = "inbound",
                text = "B 的消息",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "received",
                createdAt = now + 1
            )
        )

        val aMessages = chatDao.getAll(agentA)
        val bMessages = chatDao.getAll(agentB)

        assertEquals("Agent A 只能看到自己的消息", 1, aMessages.size)
        assertTrue("Agent A 的消息内容正确", aMessages.all { it.text == "A 的消息" })
        assertEquals("Agent B 只能看到自己的消息", 1, bMessages.size)
        assertTrue("Agent B 的消息内容正确", bMessages.all { it.text == "B 的消息" })
    }

    @Test
    fun chat_pending_messages_isolated_per_agent() = runBlocking {
        val now = System.currentTimeMillis()
        chatDao.insert(
            ChatMessageEntity(
                agentId = agentA,
                direction = "inbound",
                text = "A pending",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "pending",
                createdAt = now
            )
        )
        chatDao.insert(
            ChatMessageEntity(
                agentId = agentB,
                direction = "inbound",
                text = "B pending",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "pending",
                createdAt = now + 1
            )
        )

        val aPending = chatDao.getPendingMessages(agentA)
        val bPending = chatDao.getPendingMessages(agentB)

        assertEquals("Agent A 只能看到自己的 pending", 1, aPending.size)
        assertEquals("A pending", aPending[0].text)
        assertEquals("Agent B 只能看到自己的 pending", 1, bPending.size)
        assertEquals("B pending", bPending[0].text)
    }

    @Test
    fun chat_updateStatus_isolated_per_agent() = runBlocking {
        // 同一个 id 不可能跨 agent，但验证 updateStatus 不会误更新其他 agent 的消息
        val now = System.currentTimeMillis()
        val idA = chatDao.insert(
            ChatMessageEntity(
                agentId = agentA,
                direction = "inbound",
                text = "A",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "pending",
                createdAt = now
            )
        )
        val idB = chatDao.insert(
            ChatMessageEntity(
                agentId = agentB,
                direction = "inbound",
                text = "B",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "pending",
                createdAt = now + 1
            )
        )

        // 用 agentA 的 id 但传 agentB 的 agentId 应该不更新任何行
        chatDao.updateStatus(agentB, idA, "received", now)
        // 正确更新 agentA 的消息
        chatDao.updateStatus(agentA, idA, "received", now)

        val aMessages = chatDao.getAll(agentA)
        val bMessages = chatDao.getAll(agentB)
        assertEquals("received", aMessages[0].status)
        assertEquals("Agent B 的消息状态应保持 pending", "pending", bMessages[0].status)
    }

    @Test
    fun chat_deleteAll_only_clears_target_agent() = runBlocking {
        val now = System.currentTimeMillis()
        chatDao.insert(
            ChatMessageEntity(
                agentId = agentA,
                direction = "inbound",
                text = "A",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "received",
                createdAt = now
            )
        )
        chatDao.insert(
            ChatMessageEntity(
                agentId = agentB,
                direction = "inbound",
                text = "B",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "received",
                createdAt = now + 1
            )
        )

        chatDao.deleteAll(agentA)

        assertEquals("Agent A 已清空", 0, chatDao.getAll(agentA).size)
        assertEquals("Agent B 不受影响", 1, chatDao.getAll(agentB).size)
    }

    @Test
    fun chat_countOutboundSince_isolated_per_agent() = runBlocking {
        val now = System.currentTimeMillis()
        chatDao.insert(
            ChatMessageEntity(
                agentId = agentA,
                direction = "outbound",
                text = "A outbound",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "sent",
                createdAt = now
            )
        )
        chatDao.insert(
            ChatMessageEntity(
                agentId = agentB,
                direction = "outbound",
                text = "B outbound 1",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "sent",
                createdAt = now + 1
            )
        )
        chatDao.insert(
            ChatMessageEntity(
                agentId = agentB,
                direction = "outbound",
                text = "B outbound 2",
                audioPath = null,
                directorPrompt = null,
                state = "normal",
                status = "sent",
                createdAt = now + 2
            )
        )

        assertEquals("Agent A 有 1 条 outbound", 1, chatDao.countOutboundSince(agentA, now - 1))
        assertEquals("Agent B 有 2 条 outbound", 2, chatDao.countOutboundSince(agentB, now - 1))
    }

    @Test
    fun memories_isolated_per_agent() = runBlocking {
        val now = System.currentTimeMillis()
        memoryDao.insert(
            MemoryEntity(
                agentId = agentA,
                type = "event",
                category = "工作",
                content = "A 的工作记忆",
                importance = 4,
                source = "chat",
                createdAt = now,
                updatedAt = now
            )
        )
        memoryDao.insert(
            MemoryEntity(
                agentId = agentB,
                type = "event",
                category = "工作",
                content = "B 的工作记忆",
                importance = 4,
                source = "chat",
                createdAt = now,
                updatedAt = now
            )
        )

        val aMemories = memoryDao.getByMinImportance(agentA, 0)
        val bMemories = memoryDao.getByMinImportance(agentB, 0)

        assertEquals("Agent A 只能看到自己的记忆", 1, aMemories.size)
        assertEquals("A 的工作记忆", aMemories[0].content)
        assertEquals("Agent B 只能看到自己的记忆", 1, bMemories.size)
        assertEquals("B 的工作记忆", bMemories[0].content)
    }

    @Test
    fun memories_searchByKeyword_isolated_per_agent() = runBlocking {
        val now = System.currentTimeMillis()
        memoryDao.insert(
            MemoryEntity(
                agentId = agentA,
                type = "event",
                category = "工作",
                content = "A 一起看电影",
                importance = 3,
                source = "chat",
                createdAt = now,
                updatedAt = now
            )
        )
        memoryDao.insert(
            MemoryEntity(
                agentId = agentB,
                type = "event",
                category = "工作",
                content = "B 一起看电影",
                importance = 3,
                source = "chat",
                createdAt = now,
                updatedAt = now
            )
        )

        val aResults = memoryDao.searchByKeyword(agentA, "电影", 10)
        val bResults = memoryDao.searchByKeyword(agentB, "电影", 10)

        assertEquals("Agent A 关键词搜索只命中自己的记忆", 1, aResults.size)
        assertEquals("A 一起看电影", aResults[0].content)
        assertEquals("Agent B 关键词搜索只命中自己的记忆", 1, bResults.size)
        assertEquals("B 一起看电影", bResults[0].content)
    }

    @Test
    fun memories_deleteAll_only_clears_target_agent() = runBlocking {
        val now = System.currentTimeMillis()
        memoryDao.insert(
            MemoryEntity(
                agentId = agentA,
                type = "event",
                category = "工作",
                content = "A",
                importance = 3,
                source = "chat",
                createdAt = now,
                updatedAt = now
            )
        )
        memoryDao.insert(
            MemoryEntity(
                agentId = agentB,
                type = "event",
                category = "工作",
                content = "B",
                importance = 3,
                source = "chat",
                createdAt = now,
                updatedAt = now
            )
        )

        memoryDao.deleteAll(agentA)

        assertEquals("Agent A 已清空", 0, memoryDao.getByMinImportance(agentA, 0).size)
        assertEquals("Agent B 不受影响", 1, memoryDao.getByMinImportance(agentB, 0).size)
    }

    @Test
    fun conversation_summaries_isolated_per_agent() = runBlocking {
        val now = System.currentTimeMillis()
        summaryDao.insert(
            ConversationSummaryEntity(
                agentId = agentA,
                bucketId = 1,
                startMessageId = 1,
                endMessageId = 20,
                summary = "A 的第一段对话",
                createdAt = now,
                messageCount = 20
            )
        )
        summaryDao.insert(
            ConversationSummaryEntity(
                agentId = agentB,
                bucketId = 1,
                startMessageId = 1,
                endMessageId = 20,
                summary = "B 的第一段对话",
                createdAt = now,
                messageCount = 20
            )
        )

        val aSummaries = summaryDao.getPriorSummaries(agentA, 10)
        val bSummaries = summaryDao.getPriorSummaries(agentB, 10)

        assertEquals("Agent A 只能看到自己的摘要", 1, aSummaries.size)
        assertEquals("A 的第一段对话", aSummaries[0].summary)
        assertEquals("Agent B 只能看到自己的摘要", 1, bSummaries.size)
        assertEquals("B 的第一段对话", bSummaries[0].summary)
    }

    @Test
    fun conversation_summaries_maxBucketId_isolated_per_agent() = runBlocking {
        val now = System.currentTimeMillis()
        summaryDao.insert(
            ConversationSummaryEntity(
                agentId = agentA,
                bucketId = 1,
                startMessageId = 1,
                endMessageId = 20,
                summary = "A bucket 1",
                createdAt = now,
                messageCount = 20
            )
        )
        summaryDao.insert(
            ConversationSummaryEntity(
                agentId = agentA,
                bucketId = 2,
                startMessageId = 21,
                endMessageId = 40,
                summary = "A bucket 2",
                createdAt = now,
                messageCount = 20
            )
        )
        // Agent B 只有 bucket 1
        summaryDao.insert(
            ConversationSummaryEntity(
                agentId = agentB,
                bucketId = 1,
                startMessageId = 1,
                endMessageId = 20,
                summary = "B bucket 1",
                createdAt = now,
                messageCount = 20
            )
        )

        assertEquals("Agent A 最大 bucketId 应为 2", 2L, summaryDao.getMaxBucketId(agentA))
        assertEquals("Agent B 最大 bucketId 应为 1", 1L, summaryDao.getMaxBucketId(agentB))
    }
}
