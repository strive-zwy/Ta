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
    private val dailyStateDao = ServiceLocator.dailyStateDao
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
        val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
        val today = LocalDate.now(zoneId).format(DATE_FORMAT)
        val existing = dailyScheduleDao.getByDate(agentId, today)
        if (existing != null) {
            return@withContext parseSlots(existing.slotsJson)
        }

        // 不存在，生成当天作息
        generateTodaySchedule(agentId, config, zoneId, today)
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
        val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
        val today = LocalDate.now(zoneId).format(DATE_FORMAT)
        generateTodaySchedule(agentId, config, zoneId, today, reason, isAgentSwitch)
    }

    /**
     * 调用 LLM 生成当天作息
     *
     * @param isAgentSwitch 是否为切换 Agent 场景（导入新 Agent）
     *        true 时不注入历史记忆/对话/作息历史，避免新 Agent 沿用旧 Agent 风格
     */
    private suspend fun generateTodaySchedule(
        agentId: Long,
        config: AgentConfig,
        zoneId: ZoneId,
        dateStr: String,
        adjustReason: String = "",
        isAgentSwitch: Boolean = false
    ): List<DailySlot> {
        try {
            val now = LocalDateTime.now(zoneId)
            // 切换 Agent 时不注入旧 Agent 的历史记忆/对话/作息，只用新 persona 生成
            val memories = if (isAgentSwitch) emptyList() else memoryDao.getTopMemories(agentId, 20)
            val recentChats = if (isAgentSwitch) emptyList() else chatMessageDao.getAll(agentId).takeLast(10)

            // 清理过期未来事件（今天之前的）
            val today = now.toLocalDate()
            futureEventDao.deleteBefore(agentId, today.format(DATE_FORMAT))

            // 查询未来 7 天的事件
            val weekLater = today.plusDays(7).format(DATE_FORMAT)
            val futureEvents = futureEventDao.getRange(agentId, dateStr, weekLater)

            // 查询近 7 天作息历史（切换 Agent 时不注入，避免沿用旧 Agent 作息风格）
            val recentActivities = if (isAgentSwitch) {
                RecentActivitiesSummary(emptyMap(), emptyMap())
            } else {
                buildRecentActivitiesSummary(agentId, today, zoneId, days = 7)
            }

            // 生成昨天回顾（如果昨天有作息记录但还没生成回顾）
            if (!isAgentSwitch) {
                // 补齐昨天缺失的 DailyState（如果昨天有作息但没生成 DailyState）
                val yesterday = today.minusDays(1).format(DATE_FORMAT)
                val existingState = dailyStateDao.getByDate(agentId, yesterday)
                if (existingState == null) {
                    val yesterdaySchedule = dailyScheduleDao.getByDate(agentId, yesterday)
                    if (yesterdaySchedule != null) {
                        try {
                            DailySummaryGenerator().generateSummaryForDate(today.minusDays(1), zoneId)
                        } catch (e: Exception) {
                            Log.w(TAG, "补齐昨日 DailyState 失败", e)
                        }
                    }
                }
                generateYesterdayRecall(agentId, today, zoneId)
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
                val slotsJsonStr = json.encodeToString(slots)
                // 首次规划（无 adjustReason）时写入 originalSlotsJson 快照，当天不可变
                // adjustReason 非空时不覆盖 originalSlotsJson（保留首次的计划快照）
                // isAgentSwitch=true 时强制覆盖（切换 Agent 后用新 Agent 的计划替换旧快照）
                val existing = dailyScheduleDao.getByDate(agentId, dateStr)
                val existingOriginal = existing?.originalSlotsJson.orEmpty()
                val originalSlotsJson = when {
                    // 切换 Agent：强制覆盖为新 Agent 的计划快照
                    isAgentSwitch -> slotsJsonStr
                    // 首次生成且无快照：写入新快照
                    adjustReason.isEmpty() && existingOriginal.isBlank() -> slotsJsonStr
                    // 已有快照（无论是否调整）：保留原快照
                    existingOriginal.isNotBlank() -> existingOriginal
                    // 调整时但无快照（异常情况）：空字符串
                    else -> ""
                }
                val entity = DailyScheduleEntity(
                    agentId = agentId,
                    date = dateStr,
                    slotsJson = slotsJsonStr,
                    originalSlotsJson = originalSlotsJson,
                    isAdjusted = adjustReason.isNotEmpty(),
                    source = if (adjustReason.isNotEmpty()) "adjust" else "plan",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                dailyScheduleDao.upsertPreservingCreatedAt(entity)

                // 存"今天计划"记忆（让 Agent 记得自己今天打算做什么）
                val planSummary = slots.joinToString("；") { "${it.start}-${it.end} ${activityLabel(it)}" }
                memoryDao.insert(
                    MemoryEntity(
                        agentId = agentId,
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
                val todayEvents = futureEventDao.getByDate(agentId, dateStr)
                todayEvents.forEach { futureEventDao.markConsumed(agentId, it.id) }

                Log.d(TAG, "已生成当天作息：${slots.size} 个时段${if (adjustReason.isNotEmpty()) "（调整原因：$adjustReason）" else ""}")
                return slots
            } else {
                Log.w(TAG, "LLM 返回的作息解析失败，使用兜底作息")
                return saveFallbackSchedule(agentId, dateStr, adjustReason)
            }
        } catch (e: Exception) {
            Log.e(TAG, "生成当天作息失败，使用兜底作息", e)
            return saveFallbackSchedule(agentId, dateStr, adjustReason)
        }
    }

    /**
     * 将兜底作息写入 DB 并返回
     * 确保即使 LLM 失败，DB 中的作息也会更新（而非保留旧记录）
     */
    private suspend fun saveFallbackSchedule(agentId: Long, dateStr: String, adjustReason: String): List<DailySlot> {
        val slots = fallbackSchedule()
        val slotsJsonStr = json.encodeToString(slots)
        // 兜底作息同样需要写入 originalSlotsJson 快照（首次生成时）
        val existing = dailyScheduleDao.getByDate(agentId, dateStr)
        val existingOriginal = existing?.originalSlotsJson.orEmpty()
        val originalSlotsJson = when {
            adjustReason.isEmpty() && existingOriginal.isBlank() -> slotsJsonStr
            existingOriginal.isNotBlank() -> existingOriginal
            else -> ""
        }
        val entity = DailyScheduleEntity(
            agentId = agentId,
            date = dateStr,
            slotsJson = slotsJsonStr,
            originalSlotsJson = originalSlotsJson,
            isAdjusted = adjustReason.isNotEmpty(),
            source = "fallback",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        dailyScheduleDao.upsertPreservingCreatedAt(entity)
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
        // 注入季节信息，让 LLM 知道日出日落时间范围（避免冬天安排 5 点晨跑或夏天 18 点看夕阳）
        val month = now.monthValue
        val seasonInfo = when (month) {
            3, 4, 5 -> "春季（日出约 05:30-06:30，日落约 18:00-19:00）"
            6, 7, 8 -> "夏季（日出约 05:00-06:00，日落约 19:00-19:30）"
            9, 10, 11 -> "秋季（日出约 06:00-06:30，日落约 17:30-18:30）"
            12, 1, 2 -> "冬季（日出约 06:30-07:30，日落约 17:00-18:00）"
            else -> ""
        }
        sb.appendLine("季节：$seasonInfo")
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

        // 【重要·时态约束】无条件注入，防止把背景/履历里的过往事件当成今日活动
        // 克隆明星时空背景里写满了代表作品（已上映的电影/已播出的剧/已发行的歌），
        // 这些是过去完成的事，不是当下正在做的事。作息必须严格区分"今天能做的"与"过往履历"。
        sb.appendLine("【重要·时态约束（必须严格遵守）】")
        sb.appendLine("- 你的 background / 履历 / 记忆里提到的作品、成就、经历，凡是属于【已上映/已播出/已发行/已结束】的，都是【过去式】，不是今天在做的事")
        sb.appendLine("- 严禁把过去式作品当成今天的活动：")
        sb.appendLine("  ✗ 电影已上映却安排「看该电影的剧本」「宣传该电影」「为该电影跑通告」")
        sb.appendLine("  ✗ 剧已播完却安排「拍摄该戏」「补拍该剧」")
        sb.appendLine("  ✗ 歌已发行却安排「录制这首已发行的歌」「推广这张旧专辑」")
        sb.appendLine("  ✗ 已结束的巡演/演唱会却安排「为这场已结束的演出排练」")
        sb.appendLine("- 只有【明确正在进行中】的项目（如官方已公布正在拍的新戏 / 正在筹备的新专辑 / 正在举办的巡演）才能作为今天的活动")
        sb.appendLine("- 无法确定是否进行中时，一律按日常活动安排：练习基本功 / 健身 / 创作新东西 / 看其他作品 / 休息 / 处理琐事，不要凭空捏造拍摄/宣传/演出行程")
        sb.appendLine("- 今天的作息是安排【此刻这段生活里能做的事】，不是回顾过往履历或重温旧作品")
        sb.appendLine()

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
        sb.appendLine("  ],")
        sb.appendLine("  \"sleepContextPerturbation\": {")
        sb.appendLine("    \"stress\": 0.0-1.0,")
        sb.appendLine("    \"fatigue\": 0.0-1.0,")
        sb.appendLine("    \"mood\": -1.0~1.0")
        sb.appendLine("  }")
        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("sleepContextPerturbation 说明（可选，不填则用默认值）：")
        sb.appendLine("- stress 压力值：0.0=轻松 / 0.5=普通 / 1.0=极度压力（影响深睡比例，压力大时深睡减少）")
        sb.appendLine("- fatigue 疲劳值：0.0=精神 / 0.5=普通 / 1.0=极度疲劳（影响入睡浅睡，疲劳时入睡快）")
        sb.appendLine("- mood 心情：-1.0=低落 / 0.0=平静 / 1.0=愉悦（心情低落时深睡减少）")
        sb.appendLine("- 结合今天经历判断：工作压力大/被批评/加班 → stress↑；运动多/熬夜 → fatigue↑；心情好/有开心事 → mood↑")
        sb.appendLine()
        sb.appendLine("规则：")
        sb.appendLine("- 从今天起床时间开始（如 07:30），到最后一个 unavailable（睡觉）时段结束")
        sb.appendLine("- 最后一个时段必须是 unavailable（睡觉），且跨午夜到明早起床时间（如 22:00 - 07:30，表示今晚22点睡到明早7点半）")
        sb.appendLine("- 第一个时段是起床后的状态（normal/idle/busy 等），不要以 unavailable 开头")
        sb.appendLine("- 每个时段必须有 start / end / state / activity")
        sb.appendLine("- activity 是你具体在做什么（如 写设计稿 / 看新番 / 泡澡放松）")
        sb.appendLine("- 体现你的性格和喜好，不要机械")
        sb.appendLine("- 时间安排要合理，符合你的身份")
        sb.appendLine("- 周末和工作日应该有所不同")
        sb.appendLine("- 结合记忆中的近期活动，避免重复或补充未完成的事")
        sb.appendLine()
        sb.appendLine("【时间真实感要求（重要，体现真人作息）】")
        sb.appendLine("- 起床、吃饭、休息等短时段必须用非整点分钟（如 07:32 / 12:48 / 18:15），禁止用 08:00 / 12:00 / 18:00 这种整点")
        sb.appendLine("- 睡觉/工作这种长时段可以是整点或半点（如 22:30 / 09:00）")
        sb.appendLine("- 各分钟值要随机化，不要所有时段都用同样的分钟（避免全部都是 :30 或 :15）")
        sb.appendLine()
        sb.appendLine("【活动时间常识约束（必须严格遵守，违反就是常识错误）】")
        sb.appendLine("某些活动只能在特定时间段进行，安排前必须确认时间合理：")
        sb.appendLine("- 看夕阳/看日落：必须在日落前 30 分钟到日落时段（参考上面季节日落时间），禁止安排在下午或上午")
        sb.appendLine("- 看日出：必须在日出前后 30 分钟（参考上面季节日出时间），禁止安排在白天")
        sb.appendLine("- 吃午饭：11:30-13:30 之间，禁止 14:00 之后才吃午饭")
        sb.appendLine("- 吃晚饭：17:30-20:00 之间，禁止 16:00 之前吃晚饭")
        sb.appendLine("- 吃早饭：起床后 1 小时内，禁止安排在午饭时段")
        sb.appendLine("- 晨跑/晨练：日出后到 09:00 之前，禁止天没亮时晨跑")
        sb.appendLine("- 夜跑：晚饭后 1 小时（至少 19:00）之后，禁止下午夜跑")
        sb.appendLine("- 泡澡/洗澡：通常在晚饭后或睡前（19:00-22:30），禁止安排在上午或工作时间")
        sb.appendLine("- 午睡：12:30-14:30 之间，禁止安排在其他时段")
        sb.appendLine("- 睡觉：21:30-23:30 之间入睡，禁止 20:00 之前或 01:00 之后睡觉")
        sb.appendLine("- 逛夜市/看星星：必须在日落后（19:00+），禁止安排在白天")
        sb.appendLine("- 如果某个活动没有明确时间约束（如工作/看书/玩游戏），可以安排在任意合理时段")
        sb.appendLine("- 安排活动前先自检：这个活动在这个时间做合理吗？符合现实生活常识吗？")
        sb.appendLine()
        sb.appendLine("【过渡时段要求（重要，体现真人作息）】")
        sb.appendLine("- 真人不会一到点就立刻切换活动，活动切换时需要插入 5-15 分钟的过渡时段")
        sb.appendLine("- 过渡时段示例：从「工作」切换到「吃饭」之间插入「收拾东西，准备吃饭」5-10 分钟")
        sb.appendLine("- 从「工作」切换到「回家」：插入「通勤路上」15-30 分钟")
        sb.appendLine("- 从「出门」切换到「工作」：插入「到工位，整理东西」5-10 分钟")
        sb.appendLine("- 从「吃饭」切换到「工作」：插入「收拾餐具，刷手机消食」10-15 分钟")
        sb.appendLine("- 从「游戏」切换到「睡觉」：插入「关游戏，洗漱准备睡觉」10-15 分钟")
        sb.appendLine("- 过渡时段的 state 用 idle（摸鱼/过渡/准备 X 等低强度活动）")
        sb.appendLine("- 过渡时段的 activity 要具体描述过渡动作（如「收拾东西，准备出门」「通勤路上听播客」「关电脑伸个懒腰」）")
        sb.appendLine("- 不是每个切换都要插入过渡，只在大活动切换（工作↔娱乐、出门↔回家、娱乐↔睡觉）时插入，连续的小活动（如看书→刷手机）可以无缝衔接")
        sb.appendLine()
        sb.appendLine("【时段衔接】")
        sb.appendLine("- 时段之间可以无缝衔接（前一个 end = 后一个 start），也可以有 2-5 分钟的间隙作为自然过渡")
        sb.appendLine("- 不要留超过 5 分钟的空隙（那是规划漏洞）")
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
        agentId: Long,
        today: LocalDate,
        zoneId: ZoneId,
        days: Int = 7
    ): RecentActivitiesSummary {
        val startDate = today.minusDays(days.toLong()).format(DATE_FORMAT)
        val endDate = today.minusDays(1).format(DATE_FORMAT)  // 不含今天
        val recentSchedules = try {
            dailyScheduleDao.getRange(agentId, startDate, endDate)
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
     *
     * 同时解析 sleepContextPerturbation（可选，LLM 未输出时用默认值）
     */
    private fun parseSlotsFromReply(content: String): List<DailySlot> {
        val element = try {
            json.parseToJsonElement(content)
        } catch (e: Exception) {
            null
        }
        val obj = element as? kotlinx.serialization.json.JsonObject

        // 解析 sleepContextPerturbation（可选）
        val perturbation = parsePerturbation(obj)

        val raw = try {
            if (obj != null) {
                val slotsArr = obj["slots"] ?: return emptyList()
                json.decodeFromString<List<DailySlot>>(slotsArr.toString())
            } else {
                // 尝试从 replyText 中提取
                val startIndex = content.indexOf('[')
                val endIndex = content.lastIndexOf(']')
                if (startIndex >= 0 && endIndex > startIndex) {
                    val jsonArr = content.substring(startIndex, endIndex + 1)
                    json.decodeFromString<List<DailySlot>>(jsonArr)
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
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
        return normalizeSlots(raw, perturbation)
    }

    /**
     * 解析 sleepContextPerturbation（可选字段，LLM 未输出时用默认值）
     */
    private fun parsePerturbation(obj: kotlinx.serialization.json.JsonObject?): SleepPhaseSplitter.SleepContextPerturbation {
        if (obj == null) return SleepPhaseSplitter.SleepContextPerturbation()
        return try {
            val perturbObj = obj["sleepContextPerturbation"] as? kotlinx.serialization.json.JsonObject
                ?: return SleepPhaseSplitter.SleepContextPerturbation()
            val stress = (perturbObj["stress"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull() ?: 0.3f
            val fatigue = (perturbObj["fatigue"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull() ?: 0.3f
            val mood = (perturbObj["mood"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull() ?: 0.0f
            SleepPhaseSplitter.SleepContextPerturbation(
                stress = stress.coerceIn(0f, 1f),
                fatigue = fatigue.coerceIn(0f, 1f),
                mood = mood.coerceIn(-1f, 1f)
            )
        } catch (e: Exception) {
            SleepPhaseSplitter.SleepContextPerturbation()
        }
    }

    /**
     * 规范化 slots：
     * 1. 移除开头的 unavailable 时段（如 LLM 仍以 00:00 unavailable 开头）
     * 2. 确保最后一个时段是 unavailable（睡觉）且跨午夜到次日起床时间
     *    兼容旧 sleep 状态值
     * 3. Phase 1 分级睡眠：把跨午夜睡觉时段拆分为 3 段（入睡浅睡/深睡/将醒浅睡）
     */
    private fun normalizeSlots(
        slots: List<DailySlot>,
        perturbation: SleepPhaseSplitter.SleepContextPerturbation = SleepPhaseSplitter.SleepContextPerturbation()
    ): List<DailySlot> {
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

        // 3. Phase 1 分级睡眠：把睡觉时段拆分为 3 段
        val sleepSlot = result.last()
        // 只拆分未拆分的单段睡觉（sleepDepth==null 表示未拆分）
        if (sleepSlot.sleepDepth == null && sleepSlot.state == "unavailable") {
            try {
                val sleepStart = LocalTime.parse(sleepSlot.start)
                val wakeTime = LocalTime.parse(sleepSlot.end)
                val splitter = SleepPhaseSplitter()
                val splitSlots = splitter.split(sleepStart, wakeTime, perturbation)
                if (splitSlots != null && splitSlots.size == 3) {
                    // 替换最后一段为 3 段拆分
                    result.removeAt(result.lastIndex)
                    result.addAll(splitSlots)
                }
                // splitSlots==null 时保留原单段（fallback）
            } catch (e: Exception) {
                Log.e(TAG, "睡眠时段拆分失败，保留单段", e)
                // 保留原单段
            }
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
     * 重构为语义化总结：调 LLM 生成第一人称回顾，失败时降级为时段拼接
     */
    private suspend fun generateYesterdayRecall(agentId: Long, today: LocalDate, zoneId: ZoneId) {
        try {
            val yesterday = today.minusDays(1).format(DATE_FORMAT)
            val yesterdaySchedule = dailyScheduleDao.getByDate(agentId, yesterday) ?: return

            // 检查是否已经生成过回顾记忆（避免重复）
            val existingRecall = memoryDao.findOneByCategoryAndKeyword(agentId, "daily_recall", yesterday)
            if (existingRecall != null) return

            val slots = parseSlots(yesterdaySchedule.slotsJson)
            if (slots.isEmpty()) return

            // 尝试生成语义化总结（LLM），失败则降级为时段拼接
            val recallText = try {
                generateSemanticRecall(agentId, today.minusDays(1), slots, yesterdaySchedule.isAdjusted)
            } catch (e: Exception) {
                Log.w(TAG, "LLM 生成语义化回顾失败，降级为时段拼接", e)
                buildFallbackRecallText(yesterday, slots, yesterdaySchedule.isAdjusted)
            }

            memoryDao.insert(
                MemoryEntity(
                    agentId = agentId,
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
     * 生成语义化昨日回顾（调 LLM）
     * 注入昨日作息时段 + 昨日对话摘要，输出 100-200 字第一人称总结
     * 格式："7月29日 周三：今天赶完了设计稿，下午被领导夸了心情不错..."
     */
    private suspend fun generateSemanticRecall(
        agentId: Long,
        yesterdayDate: LocalDate,
        slots: List<DailySlot>,
        isAdjusted: Boolean
    ): String {
        val yesterdayStr = yesterdayDate.format(DATE_FORMAT)
        val dateDisplay = yesterdayDate.format(DateTimeFormatter.ofPattern("M月d日"))
        val weekDay = when (yesterdayDate.dayOfWeek) {
            DayOfWeek.MONDAY -> "周一"
            DayOfWeek.TUESDAY -> "周二"
            DayOfWeek.WEDNESDAY -> "周三"
            DayOfWeek.THURSDAY -> "周四"
            DayOfWeek.FRIDAY -> "周五"
            DayOfWeek.SATURDAY -> "周六"
            DayOfWeek.SUNDAY -> "周日"
            else -> ""
        }

        // 构造作息时段描述
        val scheduleDesc = slots.joinToString("；") { "${it.start}-${it.end} ${activityLabel(it)}" }

        // 查询昨日对话摘要（如果有）
        val summaryMemory = memoryDao.findOneByCategoryAndKeyword(agentId, "daily_summary", yesterdayStr)
        val summaryText = summaryMemory?.content?.substringAfter("对话摘要：") ?: ""

        val sb = StringBuilder()
        sb.appendLine("你是 Agent 的记忆助手，负责生成昨天的语义化回顾。")
        sb.appendLine("请基于以下昨天的作息和对话信息，生成一段 100-200 字的第一人称回顾。")
        sb.appendLine()
        sb.appendLine("要求：")
        sb.appendLine("- 用第一人称（Agent 视角）描述，如「今天赶完了设计稿，下午被领导夸了心情不错」")
        sb.appendLine("- 自然口语化，不要流水账式的时段罗列")
        sb.appendLine("- 突出关键活动和情感体验")
        sb.appendLine("- 格式开头：$dateDisplay $weekDay：")
        sb.appendLine("- 只输出回顾内容，不要加其他说明")
        sb.appendLine()
        sb.appendLine("昨日作息：$scheduleDesc")
        if (isAdjusted) {
            sb.appendLine("（当天作息有调整）")
        }
        if (summaryText.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("昨日对话摘要：$summaryText")
        }

        val messages = listOf(
            ChatMessage("system", sb.toString()),
            ChatMessage("user", "请生成昨天的语义化回顾")
        )

        val content = llmClient.chatRaw(messages)
        // 确保以日期开头
        return if (content.startsWith(dateDisplay)) {
            content
        } else {
            "$dateDisplay $weekDay：$content"
        }
    }

    /**
     * 构造 fallback 回顾文本（时段拼接，LLM 失败时降级使用）
     */
    private fun buildFallbackRecallText(
        yesterday: String,
        slots: List<DailySlot>,
        isAdjusted: Boolean
    ): String {
        val recallSummary = slots.joinToString("；") { "${it.start}-${it.end} ${activityLabel(it)}" }
        return if (isAdjusted) {
            "${yesterday}（有调整）：$recallSummary"
        } else {
            "$yesterday：$recallSummary"
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
