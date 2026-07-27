package com.agent.ta.domain

import android.util.Log
import com.agent.ta.data.local.entity.MemoryEntity
import com.agent.ta.data.local.entity.DailyScheduleEntity
import com.agent.ta.data.remote.dto.ChatMessage
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 每日摘要生成器
 *
 * 职责：生成前一天的对话摘要 + 作息回顾，存入记忆表
 * 让 Agent 能记住前几天和用户聊了什么、做了什么
 *
 * 触发时机：
 * - DailyPlanner.generateTodaySchedule 时调用（生成今天作息前，先回顾昨天）
 * - 已通过 DailyPlanner.generateYesterdayRecall 生成作息回顾
 * - 本类补充：生成对话内容摘要（不只是作息回顾）
 *
 * 摘要内容：
 * - 昨天和用户聊了什么话题
 * - 重要的对话片段（用户提到的事、Agent 的承诺、共同决定等）
 * - 情感基调（愉快/争吵/平淡）
 *
 * 与 daily_recall 的区别：
 * - daily_recall：作息回顾（做了什么时段的事）
 * - daily_summary：对话摘要（和用户聊了什么内容）
 * - 两者互补，共同构成 Agent 对昨天的完整记忆
 */
class DailySummaryGenerator {

    private val llmClient = ServiceLocator.llmClient
    private val chatMessageDao = ServiceLocator.chatMessageDao
    private val memoryDao = ServiceLocator.memoryDao
    private val dailyScheduleDao = ServiceLocator.dailyScheduleDao

    companion object {
        private const val TAG = "DailySummaryGenerator"
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("MM月dd日")
    }

    /**
     * 生成指定日期的对话摘要
     *
     * @param date 要生成摘要的日期（通常是昨天）
     * @param zoneId 时区
     * @return 是否生成成功
     */
    suspend fun generateSummaryForDate(
        date: LocalDate,
        zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val dateStr = date.format(DATE_FORMAT)
            val dateDisplay = date.format(DISPLAY_FORMAT)

            // 检查是否已生成过摘要（避免重复）
            val existingSummary = memoryDao.getTopMemories(100).any {
                it.type == "event" && it.category == "daily_summary" && it.content.contains(dateStr)
            }
            if (existingSummary) {
                Log.d(TAG, "$dateStr 的对话摘要已存在，跳过")
                return@withContext true
            }

            // 获取该日期的聊天记录
            val startTs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val endTs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val dayMessages = chatMessageDao.getAll().filter { msg ->
                msg.createdAt in startTs until endTs
            }

            if (dayMessages.isEmpty()) {
                Log.d(TAG, "$dateStr 无聊天记录，跳过摘要生成")
                return@withContext true
            }

            // 获取该日期的作息（用于补充上下文）
            val schedule = dailyScheduleDao.getByDate(dateStr)
            val scheduleSummary = schedule?.let { parseScheduleSummary(it) } ?: ""

            // 构造摘要 prompt
            val prompt = buildSummaryPrompt(dateDisplay, dayMessages, scheduleSummary)
            val messages = listOf(
                ChatMessage("system", prompt),
                ChatMessage("user", "请生成 $dateDisplay 的对话摘要")
            )

            val summaryContent = try {
                llmClient.chatRaw(messages)
            } catch (e: Exception) {
                Log.w(TAG, "LLM 生成摘要失败，使用简单摘要", e)
                // 兜底：用消息数量和关键词生成简单摘要
                buildFallbackSummary(dateDisplay, dayMessages)
            }

            // 存入记忆
            memoryDao.insert(
                MemoryEntity(
                    type = "event",
                    category = "daily_summary",
                    content = "$dateStr 对话摘要：$summaryContent",
                    importance = 3,
                    source = "summary",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )

            Log.d(TAG, "已生成 $dateStr 的对话摘要")
            true
        } catch (e: Exception) {
            Log.e(TAG, "生成每日摘要失败", e)
            false
        }
    }

    /**
     * 构造摘要生成 prompt
     */
    private fun buildSummaryPrompt(
        dateDisplay: String,
        messages: List<com.agent.ta.data.local.entity.ChatMessageEntity>,
        scheduleSummary: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("你是 Agent 的记忆助手，负责生成每日对话摘要。")
        sb.appendLine("请基于以下 $dateDisplay 的对话记录，生成简洁的摘要。")
        sb.appendLine()
        sb.appendLine("摘要要求：")
        sb.appendLine("- 100-200 字，简明扼要")
        sb.appendLine("- 包含：主要话题、用户提到的重要事、Agent 的承诺或建议、情感基调")
        sb.appendLine("- 用第一人称（Agent 视角）描述，如「和用户聊了 xxx，用户提到 xxx」")
        sb.appendLine("- 不要流水账，突出关键信息和情感")
        sb.appendLine("- 只输出摘要内容，不要加其他说明")
        sb.appendLine()

        if (scheduleSummary.isNotBlank()) {
            sb.appendLine("当天作息：$scheduleSummary")
            sb.appendLine()
        }

        sb.appendLine("对话记录（共 ${messages.size} 条）：")
        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        messages.takeLast(50).forEach { msg ->  // 最多 50 条避免 prompt 过长
            val time = timeFormat.format(java.util.Date(msg.createdAt))
            val role = if (msg.direction == "inbound") "用户" else "Agent"
            val content = buildString {
                if (!msg.emoji.isNullOrBlank()) append(msg.emoji)
                if (!msg.text.isNullOrBlank()) {
                    if (isNotEmpty()) append(" ")
                    append(msg.text)
                }
            }.ifBlank { "(空)" }
            sb.appendLine("[$time] $role：$content")
        }

        return sb.toString()
    }

    /**
     * 兜底摘要（LLM 失败时用）
     */
    private fun buildFallbackSummary(
        dateDisplay: String,
        messages: List<com.agent.ta.data.local.entity.ChatMessageEntity>
    ): String {
        val userMsgCount = messages.count { it.direction == "inbound" }
        val agentMsgCount = messages.count { it.direction == "outbound" }
        return "$dateDisplay 和用户聊了 $userMsgCount 条消息，Agent 回复了 $agentMsgCount 条"
    }

    /**
     * 解析作息摘要
     */
    private fun parseScheduleSummary(entity: DailyScheduleEntity): String {
        return try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val slots = json.decodeFromString<List<com.agent.ta.data.model.DailySlot>>(entity.slotsJson)
            slots.joinToString("；") { "${it.start}-${it.end} ${it.activity}" }
        } catch (e: Exception) {
            ""
        }
    }
}
