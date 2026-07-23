package com.agent.ta.domain

import android.util.Log
import com.agent.ta.data.local.entity.DailyScheduleEntity
import com.agent.ta.data.local.entity.FutureEventEntity
import com.agent.ta.data.local.entity.MemoryEntity
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.data.model.DailySlot
import com.agent.ta.data.remote.dto.ChatMessage
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 每日作息规划器
 *
 * 职责：
 * 1. 每天首次启动时（或前一天晚上）调 LLM 生成当天作息
 * 2. 规划依据：人格 + 近期记忆 + 星期/节假日 + 与用户互动节奏 + 明星日程参考
 * 3. Agent 可随时调整后续作息（如用户想聊天，Agent 自己推迟睡觉时间）
 *
 * 设计原则：
 * - 作息不是固定模板，每天由 Agent（LLM）自主规划
 * - Agent 有"自己的思想"，可以根据当天心情、记忆、外部参考调整
 * - 规划结果存入 DailySchedule 表，StateMachine 读取执行
 */
class DailyPlanner {

    private val llmClient = ServiceLocator.llmClient
    private val dailyScheduleDao = ServiceLocator.dailyScheduleDao
    private val memoryDao = ServiceLocator.memoryDao
    private val chatMessageDao = ServiceLocator.chatMessageDao
    private val futureEventDao = ServiceLocator.futureEventDao
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 获取当天作息（如果不存在则生成）
     *
     * @param config Agent 配置
     * @param zoneId 时区
     * @return 当天作息 slots
     */
    suspend fun getOrCreateTodaySchedule(
        config: AgentConfig,
        zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
    ): List<DailySlot> = withContext(Dispatchers.IO) {
        val today = LocalDate.now(zoneId).format(DATE_FORMAT)
        val existing = dailyScheduleDao.getByDate(today)
        if (existing != null) {
            return@withContext parseSlots(existing.slotsJson)
        }

        // 不存在，生成当天作息
        generateTodaySchedule(config, zoneId, today)
    }

    /**
     * 强制重新规划当天作息（用于 Agent 主动调整）
     */
    suspend fun regenerateTodaySchedule(
        config: AgentConfig,
        zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
        reason: String = ""
    ): List<DailySlot> = withContext(Dispatchers.IO) {
        val today = LocalDate.now(zoneId).format(DATE_FORMAT)
        generateTodaySchedule(config, zoneId, today, reason)
    }

    /**
     * 调用 LLM 生成当天作息
     */
    private suspend fun generateTodaySchedule(
        config: AgentConfig,
        zoneId: ZoneId,
        dateStr: String,
        adjustReason: String = ""
    ): List<DailySlot> {
        try {
            val now = LocalDateTime.now(zoneId)
            val memories = memoryDao.getTopMemories(20)
            val recentChats = chatMessageDao.getAll().takeLast(10)

            // 清理过期未来事件（今天之前的）
            val today = now.toLocalDate()
            futureEventDao.deleteBefore(today.format(DATE_FORMAT))

            // 查询未来 7 天的事件
            val weekLater = today.plusDays(7).format(DATE_FORMAT)
            val futureEvents = futureEventDao.getRange(dateStr, weekLater)

            // 生成昨天回顾（如果昨天有作息记录但还没生成回顾）
            generateYesterdayRecall(today, zoneId)

            val systemPrompt = buildPlanPrompt(config, now, memories, recentChats, futureEvents, adjustReason)
            val messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", "请规划你今天的作息时间表。")
            )

            // 用 chatRaw 拿原始 content，自己解析 slots
            // （不能用 chat()，因为 chat() 的 parseReply 会把整个 JSON 当 replyText）
            val rawContent = llmClient.chatRaw(messages)
            val slots = parseSlotsFromReply(rawContent)

            if (slots.isNotEmpty()) {
                val entity = DailyScheduleEntity(
                    date = dateStr,
                    slotsJson = json.encodeToString(slots),
                    isAdjusted = adjustReason.isNotEmpty(),
                    source = if (adjustReason.isNotEmpty()) "adjust" else "plan",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                dailyScheduleDao.upsert(entity)

                // 存"今天计划"记忆（让 Agent 记得自己今天打算做什么）
                val planSummary = slots.joinToString("；") { "${it.start}-${it.end} ${activityLabel(it)}" }
                memoryDao.insert(
                    MemoryEntity(
                        type = "event",
                        category = "daily_plan",
                        content = "${today.format(DateTimeFormatter.ofPattern("MM月dd日"))}计划：$planSummary",
                        importance = 2,
                        source = "plan",
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )

                // 标记今天的事件为已消费
                val todayEvents = futureEventDao.getByDate(dateStr)
                todayEvents.forEach { futureEventDao.markConsumed(it.id) }

                Log.d(TAG, "已生成当天作息：${slots.size} 个时段${if (adjustReason.isNotEmpty()) "（调整原因：$adjustReason）" else ""}")
                return slots
            } else {
                Log.w(TAG, "LLM 返回的作息解析失败，使用兜底作息")
                return saveFallbackSchedule(dateStr, adjustReason)
            }
        } catch (e: Exception) {
            Log.e(TAG, "生成当天作息失败，使用兜底作息", e)
            return saveFallbackSchedule(dateStr, adjustReason)
        }
    }

    /**
     * 将兜底作息写入 DB 并返回
     * 确保即使 LLM 失败，DB 中的作息也会更新（而非保留旧记录）
     */
    private suspend fun saveFallbackSchedule(dateStr: String, adjustReason: String): List<DailySlot> {
        val slots = fallbackSchedule()
        val entity = DailyScheduleEntity(
            date = dateStr,
            slotsJson = json.encodeToString(slots),
            isAdjusted = adjustReason.isNotEmpty(),
            source = "fallback",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        dailyScheduleDao.upsert(entity)
        Log.d(TAG, "已写入兜底作息到 DB（source=fallback）")
        return slots
    }

    /**
     * 构造作息规划 prompt
     */
    private fun buildPlanPrompt(
        config: AgentConfig,
        now: LocalDateTime,
        memories: List<MemoryEntity>,
        recentChats: List<com.agent.ta.data.local.entity.ChatMessageEntity>,
        futureEvents: List<FutureEventEntity>,
        adjustReason: String
    ): String {
        val persona = config.agent.persona
        val sb = StringBuilder()

        // 角色设定
        sb.appendLine("你是${config.agent.name}，${persona.background}")
        sb.appendLine("性格：${persona.personality.joinToString("、")}")
        sb.appendLine("职业/身份：${persona.background}")
        sb.appendLine()

        // 当前日期和星期
        val dateStr = now.toLocalDate().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))
        val weekDay = when (now.dayOfWeek) {
            DayOfWeek.MONDAY -> "星期一"
            DayOfWeek.TUESDAY -> "星期二"
            DayOfWeek.WEDNESDAY -> "星期三"
            DayOfWeek.THURSDAY -> "星期四"
            DayOfWeek.FRIDAY -> "星期五"
            DayOfWeek.SATURDAY -> "星期六"
            DayOfWeek.SUNDAY -> "星期日"
            else -> ""
        }
        sb.appendLine("当前时间：$dateStr $weekDay ${now.toLocalTime().truncatedTo(ChronoUnit.MINUTES)}")
        val isWeekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY
        sb.appendLine("今天是${if (isWeekend) "周末" else "工作日"}")
        sb.appendLine()

        // 近期记忆
        if (memories.isNotEmpty()) {
            sb.appendLine("你最近的记忆：")
            memories.take(10).forEach { memory ->
                sb.appendLine("- ${memory.content}")
            }
            sb.appendLine()
        }

        // 与用户互动节奏
        if (recentChats.isNotEmpty()) {
            val lastChat = recentChats.last()
            val hoursAgo = (System.currentTimeMillis() - lastChat.createdAt) / 3600000.0
            sb.appendLine("你和用户最近${String.format("%.1f", hoursAgo)}小时前聊过天")
            val recentUserMsgs = recentChats.count { it.direction == "inbound" }
            sb.appendLine("最近 10 条消息中有 $recentUserMsgs 条是用户主动发的")
            sb.appendLine()
        }

        // 明星日程参考
        if (config.referenceCelebrity.isNotBlank()) {
            sb.appendLine("参考人物：${config.referenceCelebrity}")
            sb.appendLine("你可以参考该人物近期的公开活动节奏，在自己的作息中加入类似活动（不强制完全一致，只是参考灵感）")
            sb.appendLine("例如：如果该人物今天有演出/直播/活动，你也可以安排类似的事做")
            sb.appendLine()
        }

        // 未来事件（从聊天中提取的）
        if (futureEvents.isNotEmpty()) {
            sb.appendLine("近期已知的事件：")
            futureEvents.forEach { event ->
                sb.appendLine("- ${event.date}：${event.description}")
            }
            sb.appendLine("如果其中包含今天的事件，请在作息中安排相关活动")
            sb.appendLine()
        }

        // 调整原因
        if (adjustReason.isNotBlank()) {
            sb.appendLine("需要调整作息的原因：$adjustReason")
            sb.appendLine("请基于这个原因重新规划你接下来的作息")
            sb.appendLine()
        }

        // 状态说明
        sb.appendLine("状态可选值：")
        sb.appendLine("- sleep: 睡觉")
        sb.appendLine("- work: 工作")
        sb.appendLine("- game: 玩游戏")
        sb.appendLine("- bath: 洗澡")
        sb.appendLine("- bored: 无聊/空闲")
        sb.appendLine()

        // 输出格式
        sb.appendLine("请用以下 JSON 格式输出你今天的作息时间表（从当前时间开始规划，覆盖到今晚 24:00）：")
        sb.appendLine("{")
        sb.appendLine("  \"replyText\": \"简要说明你今天的安排（一两句话）\",")
        sb.appendLine("  \"slots\": [")
        sb.appendLine("    {\"start\": \"HH:MM\", \"end\": \"HH:MM\", \"state\": \"sleep\", \"activity\": \"具体活动描述\"}")
        sb.appendLine("  ]")
        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("规则：")
        sb.appendLine("- 时间段必须连续，覆盖 00:00 到 24:00")
        sb.appendLine("- 每个时段必须有 start / end / state / activity")
        sb.appendLine("- activity 是你具体在做什么（如 写设计稿 / 看新番 / 泡澡放松）")
        sb.appendLine("- 体现你的性格和喜好，不要机械")
        sb.appendLine("- 时间安排要合理，符合你的身份")
        sb.appendLine("- 周末和工作日应该有所不同")
        sb.appendLine("- 结合记忆中的近期活动，避免重复或补充未完成的事")
        sb.appendLine("- 时间不要全部卡整点：起床、吃饭、休息等时段用真实的小时+分钟（如 07:23 / 12:45 / 18:17），像真人作息而不是课表")
        sb.appendLine("- 但睡觉/工作这种长时段可以是整点或半点（如 02:00 / 09:30）")

        return sb.toString()
    }

    /**
     * 从 LLM 回复中解析 slots
     */
    private fun parseSlotsFromReply(content: String): List<DailySlot> {
        return try {
            val element = json.parseToJsonElement(content)
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return emptyList()
            val slotsArr = obj["slots"] ?: return emptyList()
            json.decodeFromString<List<DailySlot>>(slotsArr.toString())
        } catch (e: Exception) {
            // 尝试从 replyText 中提取
            try {
                val startIndex = content.indexOf('[')
                val endIndex = content.lastIndexOf(']')
                if (startIndex >= 0 && endIndex > startIndex) {
                    val jsonArr = content.substring(startIndex, endIndex + 1)
                    return json.decodeFromString<List<DailySlot>>(jsonArr)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "解析 slots 失败", e2)
            }
            emptyList()
        }
    }

    /**
     * 解析 slots JSON
     */
    private fun parseSlots(slotsJson: String): List<DailySlot> {
        return try {
            json.decodeFromString<List<DailySlot>>(slotsJson)
        } catch (e: Exception) {
            Log.e(TAG, "解析 slots JSON 失败", e)
            emptyList()
        }
    }

    /**
     * 兜底作息（LLM 调用失败时使用）
     */
    private fun fallbackSchedule(): List<DailySlot> {
        val now = java.time.LocalTime.now()
        return listOf(
            DailySlot("00:00", "07:30", "sleep", "睡觉"),
            DailySlot("07:30", "08:30", "bored", "刚起床发呆"),
            DailySlot("08:30", "12:00", "work", "工作"),
            DailySlot("12:00", "13:30", "bored", "午休"),
            DailySlot("13:30", "18:00", "work", "工作"),
            DailySlot("18:00", "19:00", "bath", "洗澡"),
            DailySlot("19:00", "22:00", "game", "玩游戏"),
            DailySlot("22:00", "24:00", "bored", "睡前刷手机")
        )
    }

    /**
     * 生成昨天的回顾记忆（让 Agent 记得昨天做了什么）
     * 在生成今天作息时，如果昨天有作息记录，就补一条"昨天回顾"记忆
     */
    private suspend fun generateYesterdayRecall(today: LocalDate, zoneId: ZoneId) {
        try {
            val yesterday = today.minusDays(1).format(DATE_FORMAT)
            val yesterdaySchedule = dailyScheduleDao.getByDate(yesterday) ?: return

            // 检查是否已经生成过回顾记忆（避免重复）
            val existingRecall = memoryDao.getTopMemories(50).any {
                it.type == "event" && it.category == "daily_recall" && it.content.contains(yesterday)
            }
            if (existingRecall) return

            val slots = parseSlots(yesterdaySchedule.slotsJson)
            if (slots.isEmpty()) return

            val recallSummary = slots.joinToString("；") { "${it.start}-${it.end} ${activityLabel(it)}" }
            val isAdjusted = yesterdaySchedule.isAdjusted
            val recallText = if (isAdjusted) {
                "${yesterday}（有调整）：$recallSummary"
            } else {
                "$yesterday：$recallSummary"
            }

            memoryDao.insert(
                MemoryEntity(
                    type = "event",
                    category = "daily_recall",
                    content = recallText,
                    importance = 2,
                    source = "recall",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            Log.d(TAG, "已生成昨天($yesterday)的回顾记忆")
        } catch (e: Exception) {
            Log.e(TAG, "生成昨天回顾失败", e)
        }
    }

    /**
     * 生成 slot 的活动标签
     */
    private fun activityLabel(slot: DailySlot): String {
        val stateLabel = when (slot.state) {
            "sleep" -> "睡觉"
            "work" -> "工作"
            "game" -> "游戏"
            "bath" -> "洗澡"
            "bored" -> "空闲"
            else -> slot.state
        }
        return if (slot.activity.isNotBlank()) "${stateLabel}（${slot.activity}）" else stateLabel
    }

    companion object {
        private const val TAG = "DailyPlanner"
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
