package com.agent.ta.domain

import com.agent.ta.data.remote.dto.ReplyItem

object ReplyDeliveryPolicy {
    fun attachPureEmoji(items: List<ReplyItem>): List<ReplyItem> {
        val result = mutableListOf<ReplyItem>()
        var leadingEmoji = ""

        items.forEach { item ->
            val isPureEmoji = item.replyText.isBlank() && item.emoji.isNotBlank()
            if (isPureEmoji) {
                if (result.isEmpty()) {
                    leadingEmoji += item.emoji
                } else {
                    val last = result.last()
                    result[result.lastIndex] = last.copy(emoji = last.emoji + item.emoji)
                }
            } else if (item.replyText.isNotBlank()) {
                result += item.copy(emoji = leadingEmoji + item.emoji)
                leadingEmoji = ""
            }
        }

        return result
    }
}
