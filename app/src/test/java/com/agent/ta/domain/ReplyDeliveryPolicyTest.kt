package com.agent.ta.domain

import com.agent.ta.data.remote.dto.ReplyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyDeliveryPolicyTest {
    @Test
    fun `leading emoji attaches to first text reply`() {
        val result = ReplyDeliveryPolicy.attachPureEmoji(
            listOf(
                ReplyItem(emoji = "😊"),
                ReplyItem(replyText = "你好")
            )
        )

        assertEquals(1, result.size)
        assertEquals("你好", result[0].replyText)
        assertEquals("😊", result[0].emoji)
    }

    @Test
    fun `emoji after text attaches to previous reply`() {
        val result = ReplyDeliveryPolicy.attachPureEmoji(
            listOf(
                ReplyItem(replyText = "第一条"),
                ReplyItem(emoji = "😊"),
                ReplyItem(replyText = "第二条"),
                ReplyItem(emoji = "❤️")
            )
        )

        assertEquals(listOf("😊", "❤️"), result.map { it.emoji })
    }

    @Test
    fun `multiple pure emoji are combined`() {
        val result = ReplyDeliveryPolicy.attachPureEmoji(
            listOf(
                ReplyItem(emoji = "😊"),
                ReplyItem(emoji = "❤️"),
                ReplyItem(replyText = "你好")
            )
        )

        assertEquals("😊❤️", result.single().emoji)
    }

    @Test
    fun `all emoji batch is ignored`() {
        val result = ReplyDeliveryPolicy.attachPureEmoji(
            listOf(ReplyItem(emoji = "😊"), ReplyItem(emoji = "❤️"))
        )

        assertTrue(result.isEmpty())
    }
}
