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
import java.time.LocalTime
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
     *
     * @param isAgentSwitch 是否为切换 Agent 场景（导入新 Agent）
     *        true 时不注入历史记忆/对话/作息历史，避免新 Agent 沿用旧 Agent 风格
     */
    suspend fun regenerateTodaySchedule(
        config: AgentConfig,
        zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
        reason: String = "",
        isAgentSwitch: Boolean = false
    ): List<DailySlot> = withContext(Dispatchers.IO) {
        val today = LocalDate.now(zoneId).format(DATE_FORMAT)
        generateTodaySchedule(config, zoneId, today, reason, isAgentSwitch)
    }

    /**
     * 调用 LLM 生成当天作息
     *
     * @param isAgentSwitch 是否为切换 Agent 场景（导入新 Agent）
     *        true 时不注入历史记忆/对话/作息历史，避免新 Agent 沿用旧 Agent 风格
     */
    private suspend fun generateTodaySchedule(
        config: AgentConfig,
        zoneId: ZoneId,
        dateStr: String,
        adjustReason: String = "",
        isAgentSwitch: Boolean = false
    ): List<DailySlot> {
        try {
            val now = LocalDateTime.now(zoneId)
            // 切换 Agent 时不注入旧 Agent 的历史记忆/对话/作息，只用新 persona 生成
            val memories = if (isAgentSwitch) emptyList() else memoryDao.getTopMemories(20)
            val recentChats = if (isAgentSwitch) emptyList() else chatMessageDao.getAll().takeLast(10)

            // 清理过期未来事件（今天之前的）
            val today = now.toLocalDate()
            futureEventDao.deleteBefore(today.format(DATE_FORMAT))

            // 查询未来 7 天的事件
            val weekLater = today.plusDays(7).format(DATE_FORMAT)
            val futureEvents = futureEventDao.getRange(dateStr, weekLater)

            // 查询近 7 天作息历史（切换 Agent 时不注入，避免沿用旧 Agent 作息风格）
            val recentActivities = if (isAgentSwitch) {
                RecentActivitiesSummary(emptyMap(), emptyMap())
            } else {
                buildRecentActivitiesSummary(today, zoneId, days = 7)
            }

            // 生成昨天回顾（如果昨天有作息记录但还没生成回顾）
            if (!isAgentSwitch) {
                generateYesterdayRecall(today, zoneId)
                // 生成昨天对话摘要（让 Agent 记得昨天和用户聊了什么内容）
                try {
                    DailySummaryGenerator().generateSummaryForDate(today.minusDays(1), zoneId)
                } catch (e: Exception) {
                    Log.w(TAG, "生成昨日对话摘要失败（不影响作息生成）", e)
                }
            }

            val systemPrompt = buildPlanPrompt(
                config, now, memories, recentChats, futureEvents,
                recentActivities, adjustReason
            )
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
     *
     * v3 增强：注入近 7 天作息历史 + 活动频次统计 + 多样性引导规则，
     * 避免每天作息高度重复（用户反馈"最近几天的作息都大差不差"）
     */
    private fun buildPlanPrompt(
        config: AgentConfig,
        now: LocalDateTime,
        memories: List<MemoryEntity>,
        recentChats: List<com.agent.ta.data.local.entity.ChatMessageEntity>,
        futureEvents: List<FutureEventEntity>,
        recentActivities: RecentActivitiesSummary,
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

        // 近 7 天作息历史（核心：让 LLM 知道最近做了什么，避免重复）
        if (recentActivities.activitiesByDate.isNotEmpty()) {
            sb.appendLine("【重要】你最近 7 天的作息历史（避免今天重复）：")
            recentActivities.activitiesByDate.entries.sortedByDescending { it.key }.forEach { (date, acts) ->
                val nonSleepActs = acts.filter { it.isNotBlank() && it != "睡觉" }
                if (nonSleepActs.isNotEmpty()) {
                    sb.appendLine("- $date：${nonSleepActs.joinToString("、")}")
                }
            }
            sb.appendLine()

            // 高频活动提示
            val highFreq = recentActivities.frequency.entries
                .filter { it.value >= 2 && it.key.isNotBlank() && it.key != "睡觉" }
                .sortedByDescending { it.value }
                .take(5)
            if (highFreq.isNotEmpty()) {
                sb.appendLine("最近频繁出现的活动（今天建议换换花样）：")
                highFreq.forEach { (act, count) ->
                    sb.appendLine("- $act（${count}次）")
                }
                sb.appendLine()
            }
        }

        // 活动灵感库（基于 persona.interests + 状态类型提示，引导多样性）
        sb.appendLine(buildActivitySuggestions(persona, isWeekend))
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
        sb.appendLine("状态可选值（按能否回复和回复积极性区分）：")
        sb.appendLine("- normal: 正常（日常活动，可正常回复）")
        sb.appendLine("- busy: 忙碌（工作/游戏等专注活动，回复慢）")
        sb.appendLine("- idle: 空闲（发呆/摸鱼/休息，回复快，话多）")
        sb.appendLine("- unavailable: 无法回复（睡觉/洗澡等，不回复消息）")
        sb.appendLine()

        // 输出格式
        sb.appendLine("请用以下 JSON 格式输出你今天的作息时间表（从今天起床开始规划，到最后睡觉结束）：")
        sb.appendLine("{")
        sb.appendLine("  \"replyText\": \"简要说明你今天的安排（一两句话）\",")
        sb.appendLine("  \"slots\": [")
        sb.appendLine("    {\"start\": \"HH:MM\", \"end\": \"HH:MM\", \"state\": \"unavailable\", \"activity\": \"具体活动描述\"}")
        sb.appendLine("  ]")
        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("规则：")
        sb.appendLine("- 从今天起床时间开始（如 07:30），到最后一个 unavailable（睡觉）时段结束")
        sb.appendLine("- 最后一个时段必须是 unavailable（睡觉），且跨午夜到明早起床时间（如 22:00 - 07:30，表示今晚22点睡到明早7点半）")
        sb.appendLine("- 第一个时段是起床后的状态（normal/idle/busy 等），不要以 unavailable 开头")
        sb.appendLine("- 时间段必须连续，每个时段的 end 等于下一个时段的 start")
        sb.appendLine("- 每个时段必须有 start / end / state / activity")
        sb.appendLine("- activity 是你具体在做什么（如 写设计稿 / 看新番 / 泡澡放松）")
        sb.appendLine("- 体现你的性格和喜好，不要机械")
        sb.appendLine("- 时间安排要合理，符合你的身份")
        sb.appendLine("- 周末和工作日应该有所不同")
        sb.appendLine("- 结合记忆中的近期活动，避免重复或补充未完成的事")
        sb.appendLine("- 时间不要全部卡整点：起床、吃饭、休息等时段用真实的小时+分钟（如 07:23 / 12:45 / 18:17），像真人作息而不是课表")
        sb.appendLine("- 但睡觉/工作这种长时段可以是整点或半点（如 02:00 / 09:30）")
        // 多样性硬规则
        sb.appendLine("- 【重要多样性要求】今天至少有 1-2 个时段做最近 3 天没做过的活动，不要简单复制历史作息")
        sb.appendLine("- 避免每天都用同样的活动名（如不要每天都「玩游戏」「工作」「洗澡」），换换说法和内容（如「打新出的塞尔达」「赶设计稿」「泡澡放松」）")
        sb.appendLine("- activity 描述要具体（如「看《三体》第3章」而非「看书」；「整理书桌」「刷 B 站」「试新菜谱」「下楼散步」）")

        return sb.toString()
    }

    /**
     * 提取近 N 天的活动统计
     *
     * @return RecentActivitiesSummary 包含按日期分组的活动列表 + 活动频次 Map
     */
    private suspend fun buildRecentActivitiesSummary(
        today: LocalDate,
        zoneId: ZoneId,
        days: Int = 7
    ): RecentActivitiesSummary {
        val startDate = today.minusDays(days.toLong()).format(DATE_FORMAT)
        val endDate = today.minusDays(1).format(DATE_FORMAT)  // 不含今天
        val recentSchedules = try {
            dailyScheduleDao.getRange(startDate, endDate)
        } catch (e: Exception) {
            Log.w(TAG, "查询近 $days 天作息失败", e)
            return RecentActivitiesSummary(emptyMap(), emptyMap())
        }

        // 按日期分组的活动列表
        val byDate = mutableMapOf<String, List<String>>()
        val frequency = mutableMapOf<String, Int>()

        recentSchedules.forEach { entity ->
            val slots = parseSlots(entity.slotsJson)
            if (slots.isNotEmpty()) {
                byDate[entity.date] = slots.map { it.activity }
                slots.forEach { slot ->
                    val activity = slot.activity.trim()
                    if (activity.isNotBlank()) {
                        frequency[activity] = (frequency[activity] ?: 0) + 1
                    }
                }
            }
        }

        return RecentActivitiesSummary(byDate, frequency)
    }

    /**
     * 基于人格兴趣生成活动灵感提示
     *
     * 不是硬性要求，只是给 LLM 提供多样化活动的灵感菜单
     * LLM 可以从中挑选，也可以自由发挥
     */
    private fun buildActivitySuggestions(persona: com.agent.ta.data.model.Persona, isWeekend: Boolean): String {
        val sb = StringBuilder()
        sb.appendLine("【活动灵感库】（参考用，不必全部采用，鼓励自由发挥）：")

        // 用户配置的兴趣
        if (persona.interests.isNotEmpty()) {
            sb.appendLine("- 你的兴趣：${persona.interests.joinToString("、")}")
        }

        // 按状态分类的活动灵感
        sb.appendLine(if (isWeekend) {
            "- 周末灵感：出门逛街/看展览/见朋友/做顿大餐/看一部电影/整理房间/运动/咖啡馆发呆/追剧/玩新游戏"
        } else {
            "- 工作日灵感：专注工作/学习新技能/午休时看会儿书/下班运动/做简单的饭/处理琐事/和家人朋友通话"
        })
        sb.appendLine("- idle 状态灵感：刷手机/听播客/发呆/喝茶/撸猫/看窗外/整理桌面/随便画两笔")
        sb.appendLine("- unavailable 状态灵感：洗澡/泡澡/午睡/冥想/做家务/做饭")
        sb.appendLine("- 创意活动（让生活有质感）：尝试新菜谱/学一首歌/写日记/拍照记录/做手工/逛书店/散步去没去过的地方")
        sb.appendLine("- 社交活动：和朋友聊天/回复消息/玩多人游戏/视频通话")

        return sb.toString()
    }

    /**
     * 近期活动统计结果
     */
    private data class RecentActivitiesSummary(
        /** 按日期分组的活动列表（key=日期 "yyyy-MM-dd"，value=该日所有时段的 activity） */
        val activitiesByDate: Map<String, List<String>>,
        /** 活动频次（key=activity 文本，value=出现次数） */
        val frequency: Map<String, Int>
    )

    /**
     * 从 LLM 回复中解析 slots
     */
    private fun parseSlotsFromReply(content: String): List<DailySlot> {
        val raw = try {
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
                    json.decodeFromString<List<DailySlot>>(jsonArr)
                } else {
                    emptyList()
                }
            } catch (e2: Exception) {
                Log.e(TAG, "解析 slots 失败", e2)
                emptyList()
            }
        }
        return normalizeSlots(raw)
    }

    /**
     * 规范化 slots：
     * 1. 移除开头的 unavailable 时段（如 LLM 仍以 00:00 unavailable 开头）
     * 2. 确保最后一个时段是 unavailable（睡觉）且跨午夜到次日起床时间
     *    兼容旧 sleep 状态值
     */
    private fun normalizeSlots(slots: List<DailySlot>): List<DailySlot> {
        if (slots.isEmpty()) return slots
        var result = slots.toMutableList()

        // 1. 移除开头的 unavailable/sleep 时段
        while (result.isNotEmpty() && (result.first().state == "unavailable" || result.first().state == "sleep")) {
            result.removeAt(0)
        }
        if (result.isEmpty()) return slots // 全是 unavailable，返回原始

        // 2. 确保最后一个时段是跨午夜 unavailable（睡觉）
        val firstStart = result.first().start
        val last = result.last()
        val isSleepEnd = last.state == "unavailable" || last.state == "sleep"
        if (!isSleepEnd || last.end == "24:00" ||
            runCatching { LocalTime.parse(last.end) <= LocalTime.parse(last.start) }.getOrDefault(false).not()
        ) {
            // 最后一个时段不是跨午夜 unavailable，替换为 unavailable 跨午夜到次日起床
            result[result.lastIndex] = DailySlot(
                start = last.start,
                end = firstStart,  // 跨午夜到明早起床
                state = "unavailable",
                activity = "睡觉"
            )
        }

        return result
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
     * 结构：从起床开始，最后一个 unavailable（睡觉）跨午夜到次日起床
     */
    private fun fallbackSchedule(): List<DailySlot> {
        return listOf(
            DailySlot("07:30", "08:30", "idle", "刚起床发呆"),
            DailySlot("08:30", "12:00", "busy", "工作"),
            DailySlot("12:00", "13:30", "idle", "午休"),
            DailySlot("13:30", "18:00", "busy", "工作"),
            DailySlot("18:00", "19:00", "unavailable", "洗澡"),
            DailySlot("19:00", "22:00", "busy", "玩游戏"),
            DailySlot("22:00", "07:30", "unavailable", "睡觉")  // 跨午夜到次日 07:30
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
            "normal" -> "正常"
            "busy" -> "忙碌"
            "idle" -> "空闲"
            "unavailable" -> "休息"
            // 兼容旧状态值
            "sleep" -> "休息"
            "work", "game" -> "忙碌"
            "bath" -> "休息"
            "bored" -> "空闲"
            "happy" -> "正常"
            else -> slot.state
        }
        return if (slot.activity.isNotBlank()) "${stateLabel}（${slot.activity}）" else stateLabel
    }

    companion object {
        private const val TAG = "DailyPlanner"
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
