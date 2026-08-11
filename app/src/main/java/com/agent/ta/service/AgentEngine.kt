package com.agent.ta.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.agent.ta.data.local.entity.CommitmentEntity
import com.agent.ta.data.model.AgentState
import com.agent.ta.di.ServiceLocator
import com.agent.ta.domain.ChatInteractor
import com.agent.ta.domain.anchor.ActivityAnchor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/**
 * Agent 全局引擎（单例）
 *
 * 职责：
 * 1. 启动时通过 DailyPlanner 生成/读取当天作息
 * 2. 初始化状态机 + 调度器
 * 3. 协调状态切换后的行为（处理待回复消息、无聊主动发起）
 * 4. 管理前台服务生命周期
 *
 * 改造说明：
 * - 不再从 AgentConfig.schedule 读取固定作息
 * - 启动时调 DailyPlanner.getOrCreateTodaySchedule() 生成当天作息
 * - 状态切换基于当天作息执行
 * - Agent 可通过 ScheduleAdjuster 随时调整作息
 */
object AgentEngine {

    private const val TAG = "AgentEngine"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _currentState = MutableStateFlow(AgentState.IDLE)
    val currentState: StateFlow<AgentState> = _currentState.asStateFlow()

    private val stateMachine = StateMachine()
    private var scheduler: StateScheduler? = null
    private var boredInitiator: BoredInitiator? = null
    private var lifeEventInitiator: LifeEventInitiator? = null

    /** 当前已加载的作息日期（yyyy-MM-dd），用于跨天检测 */
    @Volatile
    private var loadedScheduleDate: String? = null

    private val lifecycleState = AtomicReference(LifecycleState.STOPPED)

    private val recoveryMutex = Mutex()

    /** ensureTodayScheduleFresh 互斥锁（防止并发重复调 LLM） */
    private val scheduleMutex = Mutex()

    /**
     * 启动引擎（App 启动时调用）
     */
    fun start(context: Context) {
        // 重入保护：快速二次调用（如 BootReceiver + App 启动）时只初始化一次
        if (!lifecycleState.compareAndSet(LifecycleState.STOPPED, LifecycleState.STARTING) &&
            !lifecycleState.compareAndSet(LifecycleState.FAILED, LifecycleState.STARTING)
        ) {
            Log.d(TAG, "AgentEngine 已启动，跳过重复初始化")
            return
        }
        Log.d(TAG, "AgentEngine 启动")
        // 使用 applicationContext 避免单例持有 Activity Context 导致内存泄漏
        val appContext = context.applicationContext

        scope.launch {
            try {
            // 0. 确保持久化的活跃 Agent 存在（首次启动插入默认 Agent 并激活），
            //    同时刷新 AgentConfigProvider 内存缓存
            ServiceLocator.activeAgentManager.ensureDefaultAgentPersisted()

            val config = com.agent.ta.di.ServiceLocator.agentConfigProvider.get()

            // 1. 获取或生成当天作息（LLM 自主规划）
            val dailyPlanner = com.agent.ta.domain.DailyPlanner()
            val slots = dailyPlanner.getOrCreateTodaySchedule(config)
            loadedScheduleDate = java.time.LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            Log.d(TAG, "当天作息：${slots.size} 个时段（日期：$loadedScheduleDate）")

            // 2. 初始化状态机
            val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
            stateMachine.init(slots, config.behavior.replyDelaySec, agentId)
            _currentState.value = stateMachine.currentState.value

            // 2.5 初始化活动锚点管理器（从作息表当前时段派生 SCHEDULE anchor）
            ServiceLocator.activityAnchorManager.getEffectiveAnchor(slots)
            Log.d(TAG, "活动锚点已初始化：${getCurrentActivityAnchor()?.activity ?: "无"}")

            // 3. 注册状态切换调度
            scheduler = StateScheduler(appContext)
            val switches = stateMachine.getUpcomingSwitches(8)
            scheduler?.scheduleNextSwitches(switches)

            // 4. 启动无聊主动发起检查 + 生活节点型主动消息
            boredInitiator = BoredInitiator(appContext)
            boredInitiator?.start()
            lifeEventInitiator = LifeEventInitiator(appContext)

            // 5. 处理被杀期间的待回复消息
            ServiceLocator.chatMessageDao.releaseProcessing(agentId)
            // BUSY 状态启动时跳过 pending 处理：忙碌期间不回复是预期行为，
            // 等 BUSY 时段结束（切换到 NORMAL/IDLE）后由 onStateSwitched 触发处理。
            // UNAVAILABLE 状态由 processPendingMessages 内部跳过。
            if (_currentState.value != AgentState.BUSY) {
                processPendingMessages(appContext)
            } else {
                Log.d(TAG, "BUSY 状态启动，跳过 pending 处理（等状态切换）")
            }

            // 5.5 承诺闹钟恢复 + 已到期承诺立即触发
            // AlarmManager 在设备重启或 App 被杀后会丢失所有闹钟，需要从 DB 恢复
            // 已到期但未触发的承诺（被杀期间错过触发时间的）立即补触发，避免"已存在未触发"状态
            try {
                val now = System.currentTimeMillis()
                val pendingCommitments = ServiceLocator.commitmentDao.getByStatus(agentId, "pending")
                    .filter { it.triggerAt != null }

                // 读取承诺定时提醒开关：开启时由系统到点主动发消息，关闭时靠 LLM 在对话中自然提醒
                val timerEnabled = ServiceLocator.userPreferences.commitmentTimerEnabled

                val scheduler = CommitmentScheduler(appContext)
                val dueList = mutableListOf<CommitmentEntity>()
                val futureList = mutableListOf<CommitmentEntity>()

                pendingCommitments.forEach { c ->
                    if (c.triggerAt!! <= now) {
                        dueList.add(c)
                    } else {
                        futureList.add(c)
                    }
                }

                if (timerEnabled) {
                    // 定时任务模式：已到期承诺立即补触发 + 未到期承诺重新注册闹钟
                    dueList.forEach { commitment ->
                        triggerCommitment(appContext, commitment)
                    }
                    futureList.forEach { commitment ->
                        scheduler.scheduleCommitmentTrigger(commitment)
                    }
                    Log.d(TAG, "承诺恢复（定时模式）：补触发 ${dueList.size} 条已到期，重新调度 ${futureList.size} 条未到期")
                } else {
                    Log.d(TAG, "承诺恢复（记忆模式）：保留 ${dueList.size} 条到期承诺供对话自然提醒")
                }
            } catch (e: Exception) {
                Log.w(TAG, "承诺闹钟恢复失败（不影响主流程）", e)
            }

            // 6. 触发首次见面问候（Task 16：替代旧 Onboarding）
            //    FirstMeetingCoordinator 内部 CAS 抢占，已完成或正在生成时静默跳过
            checkFirstMeeting(appContext)

            // 7. 注册内置观察者并启动心跳（L0 基础设施层）
            //    阶段4: 仅记录日志验证 Observer 工作
            //    阶段6: 注入 ThinkActDecider 处理状态变化
            ServiceLocator.registerObserversIfNeeded()
            ServiceLocator.heartbeat.start { changedSnapshots ->
                Log.d(TAG, "Heartbeat 检测到状态变化：${changedSnapshots.map { it.observerId }}")
                // 阶段6 将在此调用 thinkActDecider.think(changedSnapshots)

                // 承诺到期兜底触发（AlarmManager 遗漏时由 Heartbeat 补检）
                // collectChanged() 已按 hasDelta=true 过滤，此处只需确认承诺快照在变化列表中
                val commitmentSnapshot = changedSnapshots.find { it.observerId == "commitment" }
                if (commitmentSnapshot != null) {
                    val hbAgentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
                    val dueCommitments = ServiceLocator.commitmentDao.getDueCommitments(hbAgentId, System.currentTimeMillis())
                    dueCommitments.forEach { commitment ->
                        triggerCommitment(appContext, commitment)
                    }
                }
            }
            Log.d(TAG, "L0 基础设施层已启动（Observer + Heartbeat）")

            // Phase 3 情感势能：启动整点定时协程（每小时触发一次情绪积累+衰减）
            // 独立于 Heartbeat（Heartbeat 按状态变化触发，不适合做整点定时）
            startEmotionalHourlyTicker()
            lifecycleState.set(LifecycleState.RUNNING)
            } catch (e: Exception) {
                lifecycleState.set(LifecycleState.FAILED)
                Log.e(TAG, "AgentEngine 启动失败，可在下次调用时重试", e)
            }
        }
    }

    private fun triggerCommitment(context: Context, commitment: CommitmentEntity) {
        context.sendBroadcast(
            Intent(context, CommitmentTriggerReceiver::class.java).apply {
                action = CommitmentTriggerReceiver.ACTION_COMMITMENT_TRIGGER
                putExtra(CommitmentTriggerReceiver.EXTRA_COMMITMENT_ID, commitment.id)
                putExtra(CommitmentTriggerReceiver.EXTRA_AGENT_ID, commitment.agentId)
            }
        )
    }

    /**
     * Phase 3 情感势能：整点定时协程
     *
     * 每小时触发一次 EmotionalService.applyHourlyDecayAndAccumulation：
     * - 静默积累（按 Agent 当前 valence 系数积累势能）
     * - 每小时衰减（势能 -2，valence/arousal 向中性漂移）
     *
     * 实现策略：每分钟检查一次当前分钟是否为 0（整点），是则触发。
     * 这样无需依赖精确闹钟，App 进程存活时即可工作；进程被杀时由跨天清理兜底。
     */
    private fun startEmotionalHourlyTicker() {
        scope.launch {
            while (true) {
                try {
                    val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                    if (now.minute == 0) {
                        com.agent.ta.domain.EmotionalService().applyHourlyDecayAndAccumulation()
                        Log.d(TAG, "情绪整点更新完成（积累+衰减）")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "情绪整点更新失败", e)
                }
                // 每分钟检查一次
                delay(60_000L)
            }
        }
    }

    /**
     * 状态切换处理（由 StateSwitchReceiver 调用）
     *
     * v3 增强：传入 prevSlot/newSlot 给 LifeEventInitiator，识别生活节点触发主动消息
     */
    fun onStateSwitched(context: Context, newState: AgentState) {
        Log.d(TAG, "状态切换到：${newState.displayName}")
        // prevSlot 由 StateMachine.switchTo 内部记录（getCurrentSlot 在时段边界已返回新 slot，
        // 不能用于获取切换前的时段），此处通过 getPrevSlot 取回正确的切换前时段
        stateMachine.switchTo(newState)
        _currentState.value = newState
        val prevSlot = stateMachine.getPrevSlot()
        val newSlot = stateMachine.getCurrentSlot()

        // 时段切换 → 通知 ActivityAnchorManager 清除 LLM anchor 并派生新 SCHEDULE anchor
        // 这确保活动锚点始终与当前时段一致，避免 LLM 凭空改变活动状态
        ServiceLocator.activityAnchorManager.onSlotChanged(stateMachine.getTodaySlots())

        // 补充后续状态切换闹钟（避免 24h 后状态机卡死）
        // 每次 tick 触发时重新注册未来 N 个切换，确保调度始终覆盖后续时段
        val upcomingSwitches = stateMachine.getUpcomingSwitches(8)
        scheduler?.scheduleNextSwitches(upcomingSwitches)

        scope.launch {
            processPendingMessages(context)

            // 可主动发起的状态（NORMAL/BUSY/IDLE）都触发检查
            // UNAVAILABLE 不触发
            when (newState) {
                AgentState.NORMAL, AgentState.BUSY, AgentState.IDLE -> {
                    boredInitiator?.onEnterBored()
                }
                else -> {}
            }

            // 生活节点型主动消息（起床/睡觉/吃饭/洗澡/工作开始/结束）
            // 即使是 UNAVAILABLE 也调用，让 LifeEventInitiator 自己判断（如睡觉节点）
            lifeEventInitiator?.onStateSwitched(newState, prevSlot, newSlot)
        }
    }

    /**
     * Agent 自主调整作息（用户想聊天等场景）
     * 调整后重新注册调度
     *
     * 已废弃：新代码应使用 [updateSchedule]（v3 事件驱动，ScheduleAdjuster 已在 ChatInteractor 中完成局部修改）
     * 保留是为了兼容旧调用路径
     */
    fun adjustSchedule(context: Context, config: com.agent.ta.data.model.AgentConfig, reason: String) {
        scope.launch {
            val adjuster = com.agent.ta.domain.ScheduleAdjuster()
            val newSlots = adjuster.adjustTodaySchedule(config, reason)
            if (newSlots.isNotEmpty()) {
                stateMachine.updateDailySlots(newSlots)
                _currentState.value = stateMachine.currentState.value
                val switches = stateMachine.getUpcomingSwitches(8)
                scheduler?.scheduleNextSwitches(switches)
                Log.d(TAG, "作息已调整并重新注册调度")
            }
        }
    }

    /**
     * 更新作息（v3 事件驱动）
     *
     * ScheduleAdjuster 已在 ChatInteractor 中完成局部修改并持久化到 DB，
     * 本方法只负责更新状态机内存 + 重新注册调度（轻量级，不调 LLM）
     *
     * @param newSlots 新的作息列表
     */
    fun updateSchedule(newSlots: List<com.agent.ta.data.model.DailySlot>) {
        stateMachine.updateDailySlots(newSlots)
        _currentState.value = stateMachine.currentState.value
        val switches = stateMachine.getUpcomingSwitches(8)
        scheduler?.scheduleNextSwitches(switches)
        // 作息更新后刷新活动锚点（可能当前时段活动已改变）
        ServiceLocator.activityAnchorManager.getEffectiveAnchor(newSlots)
        Log.d(TAG, "作息已更新（局部调整），${newSlots.size} 个时段")
    }

    /**
     * 处理待回复消息队列
     */
    private suspend fun processPendingMessages(context: Context) {
        val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
        val pendingMessages = ServiceLocator.chatMessageDao.getPendingMessages(agentId)
        if (pendingMessages.isEmpty()) return

        val state = _currentState.value
        if (state == AgentState.UNAVAILABLE) {
            return
        }

        Log.d(TAG, "处理 ${pendingMessages.size} 条待回复消息")
        val interactor = com.agent.ta.domain.ChatInteractor(context)
        interactor.processPendingReplies()
    }

    /**
     * 暴露状态机的回复延迟（供 ChatInteractor 复用，避免延迟策略双份）
     * 返回 null 表示当前状态不可回复（应为 Defer，由调用方处理 pending）
     */
    fun getReplyDelaySec(): Long? = stateMachine.getReplyDelaySec()

    /**
     * Phase 1 分级睡眠：深睡惊醒切换到浅睡
     *
     * 轻量切换，只更新状态，不触发 onStateSwitched 的完整流程（避免触发 LifeEventInitiator/闹钟重排等）
     * 惊醒后的回复完成后，LLM 通过 scheduleAdjustment 自决回深睡或保持浅睡
     */
    fun switchToLightSleep() {
        _currentState.value = AgentState.LIGHT_SLEEP
        Log.d(TAG, "深睡惊醒：切换到浅睡状态")
    }

    /**
     * 确保作息是今天的（跨天检测）
     *
     * 问题背景：StateMachine 的 dailySlots 只在 App 启动时加载一次，
     * 如果 App 跨天运行（如凌晨 0 点后仍未重启），dailySlots 还是昨天的，
     * 导致 Agent 回复基于前一天作息（用户反馈"在运动但说在吃螺蛳粉"）。
     *
     * 调用时机：
     * - ChatInteractor.generateAgentReply 开头（每次回复前检查）
     * - LifeEventInitiator 触发前检查
     *
     * 检查逻辑：
     * - 比较 loadedScheduleDate 和当前日期
     * - 不一致时重新加载今天的作息 + 重新注册调度
     */
    suspend fun ensureTodayScheduleFresh(context: Context) {
        val today = java.time.LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        // 加锁防止两个协程同时通过检查并重复调 LLM
        scheduleMutex.withLock {
            // 日期一致且 slots 非空，才跳过
            if (loadedScheduleDate == today && stateMachine.getTodaySlots().isNotEmpty()) {
                Log.d(TAG, "作息已是今天的（$today），${stateMachine.getTodaySlots().size} 个时段，当前活动：${getCurrentActivity()}")
                return@withLock
            }

            Log.d(TAG, "需要加载今日作息（loadedDate=$loadedScheduleDate, today=$today, slots非空=${stateMachine.getTodaySlots().isNotEmpty()}）")
            val config = com.agent.ta.di.ServiceLocator.agentConfigProvider.get()
            val dailyPlanner = com.agent.ta.domain.DailyPlanner()
            val slots = dailyPlanner.getOrCreateTodaySchedule(config)
            loadedScheduleDate = today
            stateMachine.updateDailySlots(slots)
            _currentState.value = stateMachine.currentState.value
            val switches = stateMachine.getUpcomingSwitches(8)
            scheduler?.scheduleNextSwitches(switches)
            // 跨天加载新作息后刷新活动锚点
            ServiceLocator.activityAnchorManager.onSlotChanged(slots)
            Log.d(TAG, "作息已更新为今天的（$today），${slots.size} 个时段，当前活动：${getCurrentActivity()}")

            // ── 跨天历史数据清理（每天执行一次）──
            // 此处位于 scheduleMutex.withLock 内，仅在跨天/首次启动时进入，不会重复执行
            try {
                val now = System.currentTimeMillis()
                val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()

                // Step 28: 承诺超时自动过期清理
                // 清理过期承诺（超过 deadline 24 小时的未终态承诺自动标记为 expired）
                val expireBefore = now - 24 * 60 * 60 * 1000L  // 超过 deadline 24 小时
                val expired = ServiceLocator.commitmentDao.getExpiredCommitments(agentId, expireBefore)
                expired.forEach { commitment ->
                    ServiceLocator.commitmentDao.updateStatus(agentId, commitment.id, "expired")
                    CommitmentScheduler(context).cancelCommitmentTrigger(agentId, commitment.id)
                }
                // 清理已完成的旧承诺（30 天前的 completed/cancelled/expired）
                val oldCutoff = now - 30L * 24 * 60 * 60 * 1000
                ServiceLocator.commitmentDao.deleteOldCompleted(agentId, oldCutoff)

                // Step 29: 历史记忆清理策略
                // 清理 30 天前的 daily_plan 和 daily_recall（importance=2，价值递减）
                val cutoff30Ts = now - 30L * 24 * 60 * 60 * 1000
                ServiceLocator.memoryDao.deleteByCategoryBefore(agentId, "daily_plan", cutoff30Ts)
                ServiceLocator.memoryDao.deleteByCategoryBefore(agentId, "daily_recall", cutoff30Ts)

                // 清理 90 天前的 daily_summary、daily_schedule、daily_state
                val cutoff90Ts = now - 90L * 24 * 60 * 60 * 1000
                ServiceLocator.memoryDao.deleteByCategoryBefore(agentId, "daily_summary", cutoff90Ts)
                // daily_schedule 和 daily_state 用日期字符串清理
                val cutoff90Date = java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(90)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                ServiceLocator.dailyScheduleDao.deleteBefore(agentId, cutoff90Date)
                ServiceLocator.dailyStateDao.deleteBefore(agentId, cutoff90Date)

                Log.d(TAG, "跨天清理完成：过期承诺 ${expired.size} 条")

                // Phase 2 关系系统：每日信任衰减
                try {
                    com.agent.ta.domain.RelationshipService().applyDailyDecayIfNeeded()
                } catch (e: Exception) {
                    Log.e(TAG, "关系状态每日衰减失败", e)
                }

                // Phase 3 情感势能：跨天应用睡眠基线（昨晚睡眠情况 → 今天起始情绪）
                try {
                    com.agent.ta.domain.EmotionalService().applySleepBaselineIfNeeded()
                } catch (e: Exception) {
                    Log.e(TAG, "情绪睡眠基线应用失败", e)
                }
            } catch (e: Exception) {
                // 清理失败不影响主流程（作息已加载成功）
                Log.e(TAG, "跨天历史数据清理失败", e)
            }
        }
    }

    /**
     * 获取当前时段的具体活动（如"去杭州拍戏"），供 PromptBuilder 让 LLM 知道当前在做什么
     */
    fun getCurrentActivity(): String? = stateMachine.getCurrentSlot()?.activity

    /**
     * 获取当前时段（供 Observer 读取，不触发状态切换）
     */
    fun getCurrentSlot(): com.agent.ta.data.model.DailySlot? = stateMachine.getCurrentSlot()

    /**
     * 获取当前有效的活动锚点（应用侧权威状态）
     *
     * 优先级：
     * - LLM 通过 set_activity 工具设置的锚点（未过期时优先）
     * - 作息表当前时段派生的锚点（兜底）
     *
     * 供 PromptBuilder 注入 system prompt，让 LLM 始终锚定真实活动状态，
     * 避免前后回复活动状态矛盾（如上一轮说"去洗澡了"，下一轮说"还在健身"）。
     */
    fun getCurrentActivityAnchor(): ActivityAnchor? {
        return ServiceLocator.activityAnchorManager.getEffectiveAnchor(stateMachine.getTodaySlots())
    }

    /**
     * 获取今日全天作息（供 PromptBuilder 注入，让 Agent 对话时参考全天安排，不会前后矛盾）
     */
    fun getTodaySchedule(): List<com.agent.ta.data.model.DailySlot> = stateMachine.getTodaySlots()

    /**
     * 获取今日"计划 vs 实际"对比摘要（供 PromptBuilder 注入，让 Agent 反思今天的执行情况）
     *
     * - 仅当作息被调整过（isAdjusted=true）且 originalSlotsJson 非空时返回对比文本
     * - 对比原始计划与实际作息的活动差异，让 Agent 能说"今天本来想做 X 但没做"
     * - 返回 null 表示无需注入（未调整或无原始快照）
     */
    suspend fun getPlanVsActualDiff(): String? {
        return try {
            val today = java.time.LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
            val entity = ServiceLocator.dailyScheduleDao.getByDate(agentId, today) ?: return null
            if (!entity.isAdjusted || entity.originalSlotsJson.isBlank()) return null
            if (entity.originalSlotsJson == entity.slotsJson) return null  // 未实际变化

            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val original = json.decodeFromString<List<com.agent.ta.data.model.DailySlot>>(entity.originalSlotsJson)
            val actual = json.decodeFromString<List<com.agent.ta.data.model.DailySlot>>(entity.slotsJson)

            // 提取活动列表对比
            val originalActivities = original.map { it.activity }
            val actualActivities = actual.map { it.activity }
            val dropped = originalActivities.filter { it !in actualActivities }
            val added = actualActivities.filter { it !in originalActivities }

            if (dropped.isEmpty() && added.isEmpty()) return null

            buildString {
                appendLine("今天的实际作息相比原计划有调整：")
                if (dropped.isNotEmpty()) {
                    appendLine("- 没做原计划：${dropped.joinToString("、")}")
                }
                if (added.isNotEmpty()) {
                    appendLine("- 新增了：${added.joinToString("、")}")
                }
                appendLine("（你可以在对话中自然提及这个变化，如'今天本来想${dropped.firstOrNull() ?: "做点事"}，结果${added.firstOrNull() ?: "改做别的了"}'，但不要刻意提）")
            }
        } catch (e: Exception) {
            Log.w(TAG, "生成计划 vs 实际对比失败", e)
            null
        }
    }

    /**
     * 触发首次见面问候（Task 16：替代旧 Onboarding）
     *
     * 启动时检查当前活动 Agent 的 FirstMeetingPhase：
     * - NOT_STARTED → 触发 triggerFirstMeetingGreeting（内部 CAS 抢占，防并发）
     * - 其他阶段（进行中/已完成）→ 静默跳过
     *
     * 旧用户升级时，v14→v15 迁移已将已有聊天记录的 Agent 标记为 COMPLETED_WITHOUT_NICKNAME，
     * 因此不会重复问候。
     */
    private fun checkFirstMeeting(context: Context) {
        val interactor = ChatInteractor(context)
        interactor.triggerFirstMeetingGreeting()
    }

    /**
     * 配置变更后重新加载作息与状态机（导入自定义 Agent 后调用）
     *
     * suspend 函数：调用方（AgentImportManager.import）会同步等待作息重新生成 + 调度完成，
     * 避免导入返回"成功"时新 Agent 的作息尚未落库、UI 仍显示旧作息。
     *
     * @param agentId 目标 Agent 实例 ID。导入事务完成后显式传入新 agentId，
     *                确保状态机日志和作息写入正确的 Agent 数据空间。
     *                默认 null 时使用当前 active agent（适用于同 Agent 内的作息刷新场景）。
     */
    suspend fun reloadAfterConfigChanged(context: Context, agentId: Long? = null) {
        recoveryMutex.withLock {
        val config = com.agent.ta.di.ServiceLocator.agentConfigProvider.get()
        val targetAgentId = agentId ?: ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
        // 用新配置重新生成当天作息（LLM 失败也会写入 fallback，确保覆盖旧记录）
        // isAgentSwitch=true：不注入旧 Agent 的历史记忆/对话/作息历史，避免新 Agent 沿用旧风格
        val dailyPlanner = com.agent.ta.domain.DailyPlanner()
        val slots = dailyPlanner.regenerateTodaySchedule(config, isAgentSwitch = true)
        loadedScheduleDate = java.time.LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        // 重新初始化状态机：更新 agentId + slots + delays，确保状态日志写入新 Agent 数据空间
        stateMachine.init(slots, config.behavior.replyDelaySec, targetAgentId)
        _currentState.value = stateMachine.currentState.value
        scheduler?.cancelAll()
        val switches = stateMachine.getUpcomingSwitches(8)
        scheduler?.scheduleNextSwitches(switches)
        // Agent 切换后清除旧 anchor，从新作息派生
        ServiceLocator.activityAnchorManager.onSlotChanged(slots)
        Log.d(TAG, "配置变更后已重新加载作息与调度（Agent 切换，agentId=$targetAgentId），slots=${slots.size}，当前活动：${getCurrentActivity()}")
        }
    }

    /**
     * 停止引擎
     */
    fun stop(context: Context) {
        scheduler?.cancelAll()
        boredInitiator?.stop()
        lifeEventInitiator?.stop()
        lifeEventInitiator?.cleanupExpiredRecords()
        // 取消所有协程，避免资源泄漏
        scope.coroutineContext.cancelChildren()
        // 重置启动标志，允许下次 start() 重新初始化
        lifecycleState.set(LifecycleState.STOPPED)
    }

    private enum class LifecycleState {
        STOPPED,
        STARTING,
        RUNNING,
        FAILED
    }

}
