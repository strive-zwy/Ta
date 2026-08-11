package com.agent.ta.domain

import com.agent.ta.data.remote.dto.ReplyItem

object TtsTextPolicy {
    private const val MAX_UNSPLIT_LENGTH = 30
    private const val MIN_SPLIT_LENGTH = 12
    private val sentenceEndings = charArrayOf('。', '.', '!', '！', '?', '？', '…', '\n')
    private val clauseEndings = charArrayOf('，', ',', '；', ';', '：', ':')
    private val bracketActionRegex = Regex("[（(][^）)]*[）)]")
    private val emojiRegex = Regex("[\\p{So}\\p{Sk}\\x{1F1E6}-\\x{1F1FF}\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\uFE0F\\u200D]+")
    private val laughterRegex = Regex("(?<![\\p{L}\\p{N}])(?:哈{2,}|嘿{2,}|呵{2,}|嘻{2,}|噗+|h+a+h+a+)(?:[，,。.!！?？~～…]*)", RegexOption.IGNORE_CASE)

    fun sanitizeForSpeech(text: String): String {
        return text
            .replace(bracketActionRegex, " ")
            .replace(emojiRegex, " ")
            .replace(laughterRegex, " ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^[，,。.!！?？；;：:~～…\\s]+"), "")
            .trim()
    }

    fun splitLongReply(item: ReplyItem): List<ReplyItem> {
        val text = item.replyText
        if (text.length <= MAX_UNSPLIT_LENGTH) return listOf(item)

        val segments = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val remaining = text.length - start
            if (remaining <= MAX_UNSPLIT_LENGTH) {
                text.substring(start).trim().takeIf(String::isNotBlank)?.let(segments::add)
                break
            }
            val preferredEnd = (start + MAX_UNSPLIT_LENGTH).coerceAtMost(text.length)
            val minimumEnd = (start + MIN_SPLIT_LENGTH).coerceAtMost(preferredEnd)
            val splitAt = (preferredEnd downTo minimumEnd).firstOrNull { index ->
                text[index - 1] in sentenceEndings || text[index - 1] in clauseEndings
            } ?: (preferredEnd until text.length).firstOrNull { index ->
                text[index] in sentenceEndings
            }?.plus(1)
            if (splitAt == null || splitAt <= start) return listOf(item)
            text.substring(start, splitAt).trim().takeIf(String::isNotBlank)?.let(segments::add)
            start = splitAt
        }
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
