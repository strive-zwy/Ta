package com.agent.ta.domain

import com.agent.ta.data.remote.dto.ReplyItem
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTextPolicyTest {

    @Test
    fun `keeps normal multi sentence reply intact`() {
        val item = ReplyItem(
            replyText = "好的，收到。这会儿正走着呢，到家再聊。",
            directorPrompt = "自然地说"
        )

        val result = TtsTextPolicy.splitLongReply(item)

        assertEquals(listOf(item), result)
    }

    @Test
    fun `does not split normal reply at tilde`() {
        val item = ReplyItem(
            replyText = "好的，放心～被人惦记着还挺暖的。"
        )

        val result = TtsTextPolicy.splitLongReply(item)

        assertEquals(listOf(item), result)
    }

    @Test
    fun `splits overlong reply only at sentence endings`() {
        val first = "今天发生了一件很有意思的事情，我想慢慢讲给你听。"
        val second = "后来我们又绕着湖边走了一大圈，路上还碰见了一只很亲人的猫。"
        val third = "等回到家时天已经黑了，不过整个人的心情都轻松了很多。"
        val item = ReplyItem(
            replyText = first + second + third,
            action = "靠在窗边",
            directorPrompt = "轻松自然",
            emotion = "happy"
        )

        val result = TtsTextPolicy.splitLongReply(item)

        assertEquals(3, result.size)
        assertEquals(first, result[0].replyText)
        assertEquals(second, result[1].replyText)
        assertEquals(third, result[2].replyText)
        assertEquals("靠在窗边", result[0].action)
        assertEquals("轻松自然", result[0].directorPrompt)
        assertEquals("", result[1].action)
        assertEquals("", result[1].directorPrompt)
        assertEquals("happy", result[1].emotion)
    }

    @Test
    fun `keeps overlong single sentence intact when no safe boundary exists`() {
        val item = ReplyItem(replyText = "这是一段" + "很长".repeat(50))

        val result = TtsTextPolicy.splitLongReply(item)

        assertEquals(listOf(item), result)
    }
}
