package com.agent.ta.domain

import android.util.Log
import com.agent.ta.data.local.entity.MemoryEntity
import com.agent.ta.data.local.entity.DailyScheduleEntity
import com.agent.ta.data.local.entity.DailyStateEntity
import com.agent.ta.data.model.DailySlot
import com.agent.ta.data.remote.dto.ChatMessage
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
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
    private val dailyStateDao = ServiceLocator.dailyStateDao

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

            // 获取该日期的作息（用于补充上下文，也用于提取睡眠/活动信息）
            val schedule = dailyScheduleDao.getByDate(dateStr)
            val scheduleSummary = schedule?.let { parseScheduleSummary(it) } ?: ""

            // 获取该日期的聊天记录
            val startTs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val endTs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val dayMessages = chatMessageDao.getAll().filter { msg ->
                msg.createdAt in startTs until endTs
            }

            if (dayMessages.isEmpty()) {
                Log.d(TAG, "$dateStr 无聊天记录，跳过摘要生成")
                // 即使没有聊天记录，也尝试写入结构化 DailyState（用于启动补齐）
                schedule?.let { writeDailyState(dateStr, it, dayMessages, null, "") }
                return@withContext true
            }

            // 构造摘要 prompt
            val basePrompt = buildSummaryPrompt(dateDisplay, dayMessages, scheduleSummary)

            // Step 30: 注入当天承诺历史，让摘要包含承诺完成情况
            // 复用前面已计算的 startTs/endTs（基于 Asia/Shanghai 时区）
            val commitments = ServiceLocator.commitmentDao.getByDateRange(startTs, endTs)
            val prompt = if (commitments.isNotEmpty()) {
                val csb = StringBuilder(basePrompt)
                csb.appendLine()
                csb.appendLine("今天的承诺情况：")
                commitments.forEach { c ->
                    val statusDisplay = when (c.status) {
                        "pending" -> "待执行"
                        "triggered" -> "已触发"
                        "completed" -> "已完成"
                        "cancelled" -> "已取消"
                        "expired" -> "已过期"
                        else -> c.status
                    }
                    csb.appendLine("- ${c.type}：${c.content}（状态：$statusDisplay）")
                }
                csb.appendLine("请在摘要中包含承诺完成情况，如'昨天答应了和用户一起看电影，下午3点触发，用户说看完了好评'")
                csb.toString()
            } else {
                basePrompt
            }

            val messages = listOf(
                ChatMessage("system", prompt),
                ChatMessage("user", "请生成 $dateDisplay 的对话摘要")
            )

            val rawSummaryContent = try {
                llmClient.chatRaw(messages)
            } catch (e: Exception) {
                Log.w(TAG, "LLM 生成摘要失败，使用简单摘要", e)
                // 兜底：用消息数量和关键词生成简单摘要
                buildFallbackSummary(dateDisplay, dayMessages)
            }

            // 解析 LLM 输出末尾的结构化 JSON 参数，分离纯摘要文本和 JSON
            val (cleanSummary, structuredParams) = parseStructuredParams(rawSummaryContent)

            // 存入记忆（兼容原有 daily_summary 写入）
            memoryDao.insert(
                MemoryEntity(
                    type = "event",
                    category = "daily_summary",
                    content = "$dateStr 对话摘要：$cleanSummary",
                    importance = 3,
                    source = "summary",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )

            // 写入结构化 DailyStateEntity
            schedule?.let {
                writeDailyState(dateStr, it, dayMessages, structuredParams, cleanSummary)
            }

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
        val nowForSummary = System.currentTimeMillis()
        messages.takeLast(50).forEach { msg ->  // 最多 50 条避免 prompt 过长
            val timeGap = relativeTimeGap(nowForSummary - msg.createdAt)
            val role = if (msg.direction == "inbound") "用户" else "Agent"
            val content = buildString {
                if (!msg.emoji.isNullOrBlank()) append(msg.emoji)
                if (!msg.text.isNullOrBlank()) {
                    if (isNotEmpty()) append(" ")
                    append(msg.text)
                }
            }.ifBlank { "(空)" }
            sb.appendLine("（$timeGap）$role：$content")
        }

        sb.appendLine()
        sb.appendLine("请额外在回复末尾输出结构化参数（JSON 格式，单独一行）：")
        sb.appendLine("{\"mood\": 0.0, \"fatigue\": 0.5, \"stress\": 0.3, \"energy\": 0.7}")
        sb.appendLine("- mood: 昨天情绪（-1.0=低落, 0.0=平静, 1.0=愉悦）")
        sb.appendLine("- fatigue: 昨天疲劳（0.0=精神, 1.0=极度疲劳）")
        sb.appendLine("- stress: 昨天压力（0.0=轻松, 1.0=极度压力）")
        sb.appendLine("- energy: 昨天精力水平（0.0=低, 1.0=高）")

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
            val slots = parseSlots(entity.slotsJson)
            slots.joinToString("；") { "${it.start}-${it.end} ${it.activity}" }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 解析 slotsJson 为 DailySlot 列表
     */
    private fun parseSlots(slotsJson: String): List<DailySlot> {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<List<DailySlot>>(slotsJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 结构化参数（从 LLM 输出末尾解析）
     */
    data class StructuredParams(
        val mood: Float?,
        val fatigue: Float?,
        val stress: Float?,
        val energy: Float?
    )

    /**
     * 解析 LLM 输出末尾的 JSON 结构化参数
     * 匹配最后一行 {...} 格式的 JSON，提取 mood/fatigue/stress/energy
     *
     * @return Pair(纯摘要文本, 结构化参数或 null)
     */
    private fun parseStructuredParams(rawContent: String): Pair<String, StructuredParams?> {
        // 匹配包含 mood 字段的 JSON 行
        val jsonRegex = Regex("""\{[^{}]*"mood"[^{}]*\}""")
        val match = jsonRegex.find(rawContent) ?: return Pair(rawContent.trim(), null)

        val jsonStr = match.value
        // 去掉 JSON 行，保留纯摘要文本
        val cleanSummary = rawContent.removeRange(match.range).trim()

        return try {
            val mood = Regex(""""mood"\s*:\s*(-?[\d.]+)""").find(jsonStr)?.groupValues?.get(1)?.toFloatOrNull()
            val fatigue = Regex(""""fatigue"\s*:\s*(-?[\d.]+)""").find(jsonStr)?.groupValues?.get(1)?.toFloatOrNull()
            val stress = Regex(""""stress"\s*:\s*(-?[\d.]+)""").find(jsonStr)?.groupValues?.get(1)?.toFloatOrNull()
            val energy = Regex(""""energy"\s*:\s*(-?[\d.]+)""").find(jsonStr)?.groupValues?.get(1)?.toFloatOrNull()
            Pair(cleanSummary, StructuredParams(mood, fatigue, stress, energy))
        } catch (e: Exception) {
            Log.w(TAG, "解析结构化参数失败，使用原始摘要", e)
            Pair(rawContent.trim(), null)
        }
    }

    /**
     * 写入结构化 DailyStateEntity
     * 从作息提取睡眠信息、从聊天记录统计互动、从 LLM 提取情绪参数
     */
    private suspend fun writeDailyState(
        dateStr: String,
        schedule: DailyScheduleEntity,
        dayMessages: List<com.agent.ta.data.local.entity.ChatMessageEntity>,
        params: StructuredParams?,
        summary: String
    ) {
        try {
            val slots = parseSlots(schedule.slotsJson)
            val (sleepTime, wakeTime, sleepDurationMin) = extractSleepInfo(slots)
            val mainActivities = extractMainActivities(slots)
            val hadInteractionWithUser = dayMessages.any { it.direction == "inbound" }
            val interactionCount = dayMessages.count { it.direction == "inbound" }

            dailyStateDao.upsertPreservingCreatedAt(
                DailyStateEntity(
                    date = dateStr,
                    sleepTime = sleepTime,
                    wakeTime = wakeTime,
                    sleepDurationMin = sleepDurationMin,
                    mood = params?.mood,
                    fatigue = params?.fatigue,
                    stress = params?.stress,
                    energy = params?.energy,
                    mainActivities = mainActivities,
                    specialEvents = "[]",  // 暂无特殊事件提取，预留字段
                    hadInteractionWithUser = hadInteractionWithUser,
                    interactionCount = interactionCount,
                    summary = summary
                )
            )
            Log.d(TAG, "已写入 $dateStr 的结构化 DailyState")
        } catch (e: Exception) {
            Log.e(TAG, "写入 DailyState 失败", e)
        }
    }

    /**
     * 从作息 slots 提取睡眠信息
     * - sleepTime: 最后一个 state="unavailable" 且非 sleepDepth="light" 的 slot 的 start
     * - wakeTime: 第一个非 unavailable 的 slot 的 start
     * - sleepDurationMin: 睡眠时长（分钟），处理跨午夜情况
     */
    private fun extractSleepInfo(slots: List<DailySlot>): Triple<String?, String?, Int?> {
        if (slots.isEmpty()) return Triple(null, null, null)

        // sleepTime: 最后一个 unavailable 且非 light 的 slot 的 start
        val sleepSlot = slots.lastOrNull {
            it.state == "unavailable" && it.sleepDepth != "light"
        }
        val sleepTime = sleepSlot?.start

        // wakeTime: 第一个非 unavailable 的 slot 的 start
        val wakeSlot = slots.firstOrNull { it.state != "unavailable" }
        val wakeTime = wakeSlot?.start

        // 计算睡眠时长（处理跨午夜情况）
        val sleepDurationMin = if (sleepTime != null && wakeTime != null) {
            calcSleepDurationMin(sleepTime, wakeTime)
        } else null

        return Triple(sleepTime, wakeTime, sleepDurationMin)
    }

    /**
     * 计算睡眠时长（分钟），处理跨午夜情况
     * 如 23:00 睡 -> 07:30 起 = 8 小时 30 分 = 510 分钟
     */
    private fun calcSleepDurationMin(sleepTime: String, wakeTime: String): Int? {
        return try {
            val (sleepHour, sleepMin) = sleepTime.split(":").let { it[0].toInt() to it[1].toInt() }
            val (wakeHour, wakeMin) = wakeTime.split(":").let { it[0].toInt() to it[1].toInt() }
            var sleepTotal = sleepHour * 60 + sleepMin
            var wakeTotal = wakeHour * 60 + wakeMin
            // 如果睡眠时间大于起床时间，说明跨午夜（如 23:00 睡 -> 07:30 起）
            if (sleepTotal > wakeTotal) {
                wakeTotal += 24 * 60
            }
            wakeTotal - sleepTotal
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从作息 slots 提取主要活动关键词
     * 过滤掉睡眠/休息类活动，返回 JSON 数组字符串
     */
    private fun extractMainActivities(slots: List<DailySlot>): String {
        val activities = slots
            .map { it.activity }
            .filter { it.isNotBlank() }
            .filter { activity ->
                // 过滤掉睡眠/休息类活动
                val lower = activity.lowercase()
                !lower.contains("睡") && !lower.contains("休息") &&
                !lower.contains("unavailable")
            }
            .distinct()

        // 构造 JSON 数组字符串
        return activities.joinToString(prefix = "[", postfix = "]") {
            "\"${it.replace("\"", "\\\"")}\""
        }
    }
}
