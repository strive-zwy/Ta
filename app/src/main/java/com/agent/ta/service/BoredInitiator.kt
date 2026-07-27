package com.agent.ta.service

import android.content.Context
import android.util.Log
import com.agent.ta.data.model.AgentState
import com.agent.ta.data.model.StateInitiate
import com.agent.ta.di.ServiceLocator
import com.agent.ta.domain.ChatInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * 主动发起判定器（v2 基于 ThinkActDecider）
 *
 * 三层决策架构：
 * 1. 预筛选层（BoredInitiator 自身，不调 LLM）：
 *    - 静音时段检查（23:00-08:00）
 *    - 频率控制（概率 + 冷却 30 分钟 + 每日上限）
 *    - 状态检查（UNAVAILABLE 不发起）
 *    - 配置开关检查
 *
 * 2. Think 层（ThinkActDecider.think，调 LLM）：
 *    - 用户活跃度判断（30 分钟内发过消息 → SKIP）
 *    - 失败次数累积判断（prior_attempts >= 2 → SKIP）
 *    - LLM 深度判断话题是否合适
 *
 * 3. Act 层（ThinkActDecider.act，不调 LLM）：
 *    - 基于 persona 构造话题引导
 *    - 输出 topicHint 注入 PromptBuilder Zone C
 *
 * 与 v1 的关键差异：
 * - v1 只靠概率随机发起，话题完全交给 LLM 即兴发挥 → 容易发"摸鱼/好累"等重复模板
 * - v2 通过 Think 判断"现在是否适合发起 + 话题方向"，再由 Act 构造引导，让主动发起更自然、更有上下文感
 * - v2 保留 v1 的频率控制作为预筛选，避免过度调用 LLM
 *
 * 频次换算：frequencyPerDay=N → 每5分钟检查概率 = N/(24*12)
 */
class BoredInitiator(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checkJob: Job? = null

    private val prefs = ServiceLocator.userPreferences
    private val thinkActDecider = ServiceLocator.thinkActDecider
    private val chatDao = ServiceLocator.chatMessageDao
    private val configProvider = ServiceLocator.agentConfigProvider

    /** 最近失败尝试记录（时间戳列表，1 小时窗口）
     *  用于 Think 阶段的 prior_attempts 参数，避免在用户长时间不响应时反复调 LLM */
    private val recentFailures = mutableListOf<Long>()

    companion object {
        private const val TAG = "BoredInitiator"
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L  // 5 分钟
        private const val COOLDOWN_MS = 30 * 60 * 1000L       // 固定冷却 30 分钟
        private const val FAILURE_WINDOW_MS = 60 * 60 * 1000L // 失败窗口 1 小时
        private const val MAX_RECENT_FAILURES = 2              // 最近失败次数上限
        private val SILENT_START = 23
        private val SILENT_END = 8
    }

    /**
     * 取某状态的主动发起配置
     * 返回 Pair(enabled, probability)
     */
    private fun resolveInitiateConfig(state: AgentState): Pair<Boolean, Float> {
        val config = configProvider.get()
        val perState = config.behavior.perStateInitiate[state.id]
        if (perState != null) {
            val probability = StateInitiate.levelToProbability(perState.initiateLevel)
            return Pair(perState.enabled, probability)
        }
        // Admin v1 兼容：IDLE 状态用 boredInitiate 字段
        if (state == AgentState.IDLE) {
            val v1 = config.behavior.boredInitiate
            return Pair(v1.enabled, v1.probabilityPer5min)
        }
        // 完全无配置 → 用默认档位（normal = 5%）
        return Pair(true, StateInitiate.levelToProbability(StateInitiate.NORMAL))
    }

    fun start() {
        // 默认不启动，进入可主动发起的状态时才启动
    }

    /**
     * 进入某状态时调用
     */
    fun onEnterBored() {
        if (!prefs.boredInitiateEnabled) return
        val state = AgentEngine.currentState.value
        val (enabled, probability) = resolveInitiateConfig(state)
        if (!enabled || probability <= 0f) return
        checkJob?.cancel()
        checkJob = scope.launch {
            Log.d(TAG, "进入 ${state.displayName} 状态，启动主动发起检查（probability=$probability）")
            // 延迟 2 分钟后开始检查，避免状态切换瞬间频繁打扰
            delay(2 * 60 * 1000)
            while (true) {
                val (curEnabled, curProbability) = resolveInitiateConfig(AgentEngine.currentState.value)
                if (!curEnabled || curProbability <= 0f) break
                checkAndInitiate()
                delay(CHECK_INTERVAL_MS)
            }
            Log.d(TAG, "离开可主动发起状态，停止检查")
        }
    }

    /**
     * 判定并执行主动发起（v2 三层架构）
     */
    private suspend fun checkAndInitiate() {
        val state = AgentEngine.currentState.value
        val (enabled, probability) = resolveInitiateConfig(state)
        if (!enabled || probability <= 0f) return

        // ════ 预筛选层 ════
        // 1. 静音时段检查
        val hour = java.time.LocalTime.now().hour
        if (hour >= SILENT_START || hour < SILENT_END) {
            Log.d(TAG, "静音时段，跳过")
            return
        }

        // 2. 冷却检查（固定 30 分钟）
        val recentCount = chatDao.countOutboundSince(
            System.currentTimeMillis() - COOLDOWN_MS
        )
        if (recentCount > 0) {
            Log.d(TAG, "距上次主动发起不足 30 分钟，跳过")
            return
        }

        // 3. 概率判定
        if (Random.nextFloat() > probability) {
            Log.d(TAG, "概率判定未通过（p=$probability），跳过")
            return
        }

        // 4. 清理过期失败记录
        pruneExpiredFailures()

        // 5. 失败次数预检查（避免无谓调 LLM）
        if (recentFailures.size >= MAX_RECENT_FAILURES) {
            Log.d(TAG, "最近失败 ${recentFailures.size} 次，跳过 LLM Think")
            return
        }

        // ════ Think 层 ════
        Log.d(TAG, "预筛选通过，调用 ThinkActDecider.think")
        val observerSnapshots = ServiceLocator.observerRegistry.collectAll()
        val todayProactiveSent = countTodayProactiveSent()
        val priorAttempts = recentFailures.size

        val thinkResult = thinkActDecider.think(
            observerSnapshots = observerSnapshots,
            todayProactiveSent = todayProactiveSent,
            priorAttempts = priorAttempts
        )

        if (!thinkResult.shouldAct) {
            Log.d(TAG, "Think 判定 SKIP：${thinkResult.reason}")
            recordFailure()
            return
        }

        // ════ Act 层 ════
        val actResult = thinkActDecider.act(
            thinkResult = thinkResult,
            config = configProvider.get(),
            state = state,
            observerSnapshots = observerSnapshots
        )

        if (!actResult.shouldProceed) {
            Log.d(TAG, "Act 阶段否决")
            recordFailure()
            return
        }

        // ════ 执行主动发起 ════
        Log.d(TAG, "触发主动发起（state=${state.displayName}, topic=${thinkResult.topic}）")
        val interactor = ChatInteractor(context)
        interactor.agentInitiate(actResult.topicHint)

        // 成功发起，清理失败记录
        recentFailures.clear()
    }

    /**
     * 查询今日已主动发起次数
     */
    private suspend fun countTodayProactiveSent(): Int {
        val zone = ZoneId.of("Asia/Shanghai")
        val todayStart = LocalDate.now(zone)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        return chatDao.countOutboundSince(todayStart)
    }

    /**
     * 记录一次失败尝试
     */
    private fun recordFailure() {
        recentFailures.add(System.currentTimeMillis())
        pruneExpiredFailures()
    }

    /**
     * 清理过期失败记录（1 小时窗口外）
     */
    private fun pruneExpiredFailures() {
        val threshold = System.currentTimeMillis() - FAILURE_WINDOW_MS
        recentFailures.removeAll { it < threshold }
    }

    fun stop() {
        checkJob?.cancel()
    }
}
