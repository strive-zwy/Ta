package com.agent.ta.ui.screens.chat

import com.agent.ta.data.local.entity.ChatMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageMergePolicyTest {
    @Test
    fun `same id message replaces stale status`() {
        val pending = message(id = 1, status = "pending", createdAt = 100)
        val received = pending.copy(status = "received", repliedAt = 200)

        val result = ChatMessageMergePolicy.merge(listOf(pending), listOf(received))

        assertEquals(1, result.size)
        assertEquals("received", result.single().status)
        assertEquals(200L, result.single().repliedAt)
    }

    @Test
    fun `new messages append in chronological order`() {
        val first = message(id = 1, status = "received", createdAt = 100)
        val third = message(id = 3, status = "sent", createdAt = 300)
        val second = message(id = 2, status = "sent", createdAt = 200)

        val result = ChatMessageMergePolicy.merge(listOf(first), listOf(third, second))

        assertEquals(listOf(1L, 2L, 3L), result.map { it.id })
    }

    @Test
    fun `visible updates include loaded status changes and newer messages only`() {
        val loaded = message(id = 2, status = "pending", createdAt = 200)
        val oldUnloaded = message(id = 1, status = "received", createdAt = 100)
        val loadedUpdate = loaded.copy(status = "received")
        val newer = message(id = 3, status = "sent", createdAt = 300)

        val result = ChatMessageMergePolicy.visibleUpdates(
            current = listOf(loaded),
            allMessages = listOf(oldUnloaded, loadedUpdate, newer),
            newestLoadedCreatedAt = 200
        )

        assertEquals(listOf(2L, 3L), result.map { it.id })
        assertEquals("received", result.first().status)
    }

    private fun message(id: Long, status: String, createdAt: Long) = ChatMessageEntity(
        id = id,
        agentId = 1,
        direction = if (id == 1L) "inbound" else "outbound",
        text = "message-$id",
        audioPath = null,
        directorPrompt = null,
        state = "busy",
        status = status,
        createdAt = createdAt
    )
}
