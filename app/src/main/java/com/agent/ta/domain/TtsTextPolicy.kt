package com.agent.ta.domain

import com.agent.ta.data.remote.dto.ReplyItem

object TtsTextPolicy {
    private const val MAX_UNSPLIT_LENGTH = 60
    private val sentenceEndings = charArrayOf('。', '.', '!', '！', '?', '？', '…', '\n')

    fun splitLongReply(item: ReplyItem): List<ReplyItem> {
        val text = item.replyText
        if (item.emoji.isNotBlank() || text.length <= MAX_UNSPLIT_LENGTH) return listOf(item)

        val segments = mutableListOf<String>()
        val current = StringBuilder()
        text.forEach { character ->
            current.append(character)
            if (character in sentenceEndings && current.length >= 2) {
                current.toString().trim().takeIf { it.isNotBlank() }?.let(segments::add)
                current.clear()
            }
        }
        current.toString().trim().takeIf { it.isNotBlank() }?.let(segments::add)
        if (segments.size <= 1) return listOf(item)

        return segments.mapIndexed { index, segment ->
            if (index == 0) {
                item.copy(replyText = segment)
            } else {
                item.copy(replyText = segment, action = "", directorPrompt = "", emoji = "")
            }
        }
    }
}
