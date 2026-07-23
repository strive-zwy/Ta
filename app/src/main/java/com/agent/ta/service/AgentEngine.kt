package com.agent.ta.service

import android.content.Context
import android.util.Log
import com.agent.ta.data.local.entity.OnboardingStateEntity
import com.agent.ta.data.model.AgentState
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

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

    private val _currentState = MutableStateFlow(AgentState.BORED)
    val currentState: StateFlow<AgentState> = _currentState.asStateFlow()

    private val stateMachine = StateMachine()
    private var scheduler: StateScheduler? = null
    private var boredInitiator: BoredInitiator? = null

    /**
     * 启动引擎（App 启动时调用）
     */
    fun start(context: Context) {
        Log.d(TAG, "AgentEngine 启动")

        scope.launch {
            // 0. 从 DB 加载已导入的自定义 Agent 配置（若有），否则用默认
            com.agent.ta.di.ServiceLocator.agentConfigProvider.reload()

            val config = com.agent.ta.di.ServiceLocator.agentConfigProvider.get()

            // 1. 获取或生成当天作息（LLM 自主规划）
            val dailyPlanner = com.agent.ta.domain.DailyPlanner()
            val slots = dailyPlanner.getOrCreateTodaySchedule(config)
            Log.d(TAG, "当天作息：${slots.size} 个时段")

            // 2. 初始化状态机
            stateMachine.init(slots, config.behavior.replyDelaySec)
            _currentState.value = stateMachine.currentState.value

            // 3. 注册状态切换调度
            scheduler = StateScheduler(context)
            val switches = stateMachine.getUpcomingSwitches(8)
            scheduler?.scheduleNextSwitches(switches)

            // 4. 启动无聊主动发起检查
            boredInitiator = BoredInitiator(context)
            boredInitiator?.start()

            // 5. 处理被杀期间的待回复消息
            processPendingMessages(context)

            // 6. 检查 Onboarding 状态
            checkOnboarding(context)
        }
    }

    /**
     * 状态切换处理（由 StateSwitchReceiver 调用）
     */
    fun onStateSwitched(context: Context, newState: AgentState) {
        Log.d(TAG, "状态切换到：${newState.displayName}")
        stateMachine.switchTo(newState)
        _currentState.value = newState

        scope.launch {
            processPendingMessages(context)

            // 可主动发起的状态（BORED/HAPPY/WORK/GAME）都触发检查
            // SLEEP/BATH 不触发
            when (newState) {
                AgentState.BORED, AgentState.HAPPY, AgentState.WORK, AgentState.GAME -> {
                    boredInitiator?.onEnterBored()
                }
                else -> {}
            }
        }
    }

    /**
     * Agent 自主调整作息（用户想聊天等场景）
     * 调整后重新注册调度
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
     * 处理待回复消息队列
     */
    private suspend fun processPendingMessages(context: Context) {
        val pendingMessages = ServiceLocator.chatMessageDao.getPendingMessages()
        if (pendingMessages.isEmpty()) return

        val state = _currentState.value
        if (state == AgentState.SLEEP || state == AgentState.BATH) {
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
     * 获取当前时段的具体活动（如"去杭州拍戏"），供 PromptBuilder 让 LLM 知道当前在做什么
     */
    fun getCurrentActivity(): String? = stateMachine.getCurrentSlot()?.activity

    /**
     * 获取今日全天作息（供 PromptBuilder 注入，让 Agent 对话时参考全天安排，不会前后矛盾）
     */
    fun getTodaySchedule(): List<com.agent.ta.data.model.DailySlot> = stateMachine.getTodaySlots()

    /**
     * 检查 Onboarding 状态
     *
     * 启动时：
     * - 若无 onboarding 记录且无聊天消息 → 标记 not_started，并启动 Onboarding（Agent 主动打招呼）
     * - 若 onboarding 已完成 → 跳过
     * - 若 onboarding 处于 in_progress → 不重启（等用户回复驱动推进）
     */
    private suspend fun checkOnboarding(context: Context) {
        val state = ServiceLocator.onboardingStateDao.get()
        if (state == null) {
            ServiceLocator.onboardingStateDao.upsert(
                OnboardingStateEntity(
                    phase = "not_started",
                    currentStep = 0,
                    totalSteps = 4
                )
            )
        }
        // 仅在全新用户（无任何聊天消息 + 未启动过 onboarding）时启动 Onboarding
        val chatCount = ServiceLocator.chatMessageDao.getAll().size
        if ((state == null || state.phase == "not_started") && chatCount == 0) {
            startOnboarding(context)
        }
    }

    /**
     * 启动 Onboarding 对话流程
     */
    fun startOnboarding(context: Context) {
        val manager = com.agent.ta.domain.OnboardingManager(context)
        manager.start()
    }

    /**
     * 用户发消息后驱动 Onboarding 推进（若处于 Onboarding 阶段）
     * 由 ChatInteractor 在回复完成后调用
     */
    suspend fun onUserRepliedForOnboarding(context: Context) {
        val state = ServiceLocator.onboardingStateDao.get() ?: return
        if (state.phase != "in_progress") return
        val manager = com.agent.ta.domain.OnboardingManager(context)
        manager.onUserReplied()
    }

    /**
     * 配置变更后重新加载作息与状态机（导入自定义 Agent 后调用）
     *
     * suspend 函数：调用方（AgentImportManager.import）会同步等待作息重新生成 + 调度完成，
     * 避免导入返回"成功"时新 Agent 的作息尚未落库、UI 仍显示旧作息。
     */
    suspend fun reloadAfterConfigChanged(context: Context) {
        val config = com.agent.ta.di.ServiceLocator.agentConfigProvider.get()
        // 用新配置重新生成当天作息（LLM 失败也会写入 fallback，确保覆盖旧记录）
        val dailyPlanner = com.agent.ta.domain.DailyPlanner()
        val slots = dailyPlanner.regenerateTodaySchedule(config)
        stateMachine.updateDailySlots(slots)
        _currentState.value = stateMachine.currentState.value
        scheduler?.cancelAll()
        val switches = stateMachine.getUpcomingSwitches(8)
        scheduler?.scheduleNextSwitches(switches)
        Log.d(TAG, "配置变更后已重新加载作息与调度，slots=${slots.size}")
    }

    /**
     * 停止引擎
     */
    fun stop(context: Context) {
        scheduler?.cancelAll()
        boredInitiator?.stop()
    }
}
