package com.agent.ta.service

import android.content.Context
import android.util.Log
import com.agent.ta.data.model.AgentState
import com.agent.ta.di.ServiceLocator
import com.agent.ta.domain.ChatInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 主动发起判定器
 *
 * 不止 BORED 状态，WORK/GAME/HAPPY 等清醒状态也按低概率主动发消息：
 * - 工作中偶尔吐槽"好累啊" / "想摸鱼"
 * - 吃饭时段主动问"你吃饭了吗"
 * - 游戏中分享"刚翻车了" / "上分了"
 * - 无聊时按较高概率发起话题（原行为）
 * - 开心时偶尔主动分享心情
 *
 * 优先级：
 * 1. Admin v2 per_state_initiate[state.id]（按状态精确配置 enabled/probability/interval_min/cooldown_min/candidates）
 * 2. Admin v1 bored_initiate（仅对 BORED 状态生效）
 * 3. 写死默认值兜底
 *
 * 规则：
 * - 每 5 分钟判定一次
 * - 两次主动发起之间至少间隔 N 分钟（由配置决定，默认 30 分钟）
 * - 静音时段（23:00-08:00）不主动发
 * - 各状态概率不同
 */
class BoredInitiator(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checkJob: Job? = null

    private val prefs = ServiceLocator.userPreferences

    companion object {
        private const val TAG = "BoredInitiator"
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L  // 5 分钟
        private const val DEFAULT_MIN_INTERVAL_MS = 30 * 60 * 1000L   // 30 分钟
        private val SILENT_START = 23
        private val SILENT_END = 8

        /**
         * 各状态的主动发起默认概率（每 5 分钟）—— 仅在配置缺失时兜底
         * - BORED 最高，是主要发起场景
         * - HAPPY/WORK/GAME 低概率，偶尔主动吐槽/分享
         * - SLEEP/BATH 不主动发
         */
        private fun defaultProbabilityFor(state: AgentState): Float = when (state) {
            AgentState.BORED -> 0.3f
            AgentState.HAPPY -> 0.2f
            AgentState.WORK -> 0.08f
            AgentState.GAME -> 0.1f
            AgentState.SLEEP, AgentState.BATH -> 0f
        }

        /**
         * 是否在该状态启动主动发起检查
         */
        private fun shouldCheck(probability: Float): Boolean = probability > 0f
    }

    /**
     * 取某状态的主动发起配置（Admin v2 per_state_initiate 优先，回退默认）
     * 返回 Pair(probability, intervalMin)
     */
    private fun resolveInitiateConfig(state: AgentState): Pair<Float, Int> {
        val config = ServiceLocator.agentConfigProvider.get()
        val perState = config.behavior.perStateInitiate[state.id]
        if (perState != null) {
            // Admin v2: 直接用 per_state_initiate 中的配置
            if (!perState.enabled) return Pair(0f, 0)
            return Pair(perState.probability, perState.intervalMin.coerceAtLeast(5))
        }
        // Admin v1 兜底：BORED 状态用 boredInitiate 字段
        if (state == AgentState.BORED) {
            val v1 = config.behavior.boredInitiate
            if (!v1.enabled) return Pair(0f, 0)
            return Pair(v1.probabilityPer5min, v1.minIntervalMin.coerceAtLeast(5))
        }
        // 完全无配置 → 用写死默认值
        val defaultProb = defaultProbabilityFor(state)
        return Pair(defaultProb, 30)
    }

    fun start() {
        // 默认不启动，进入可主动发起的状态时才启动
    }

    /**
     * 进入某状态时调用（原 onEnterBored，现扩展为多状态）
     */
    fun onEnterBored() {
        if (!prefs.boredInitiateEnabled) return
        val state = AgentEngine.currentState.value
        val (probability, _) = resolveInitiateConfig(state)
        if (!shouldCheck(probability)) return
        checkJob?.cancel()
        checkJob = scope.launch {
            Log.d(TAG, "进入 ${state.displayName} 状态，启动主动发起检查（p=$probability）")
            // 延迟 2 分钟后开始检查，避免状态切换瞬间频繁打扰
            delay(2 * 60 * 1000)
            while (shouldCheck(resolveInitiateConfig(AgentEngine.currentState.value).first)) {
                checkAndInitiate()
                delay(CHECK_INTERVAL_MS)
            }
            Log.d(TAG, "离开可主动发起状态，停止检查")
        }
    }

    /**
     * 判定并执行主动发起
     */
    private suspend fun checkAndInitiate() {
        val state = AgentEngine.currentState.value
        val (probability, intervalMin) = resolveInitiateConfig(state)

        // 1. 静音时段检查
        val hour = java.time.LocalTime.now().hour
        if (hour >= SILENT_START || hour < SILENT_END) {
            Log.d(TAG, "静音时段，跳过")
            return
        }

        // 2. 最近是否已主动发过（按配置的 intervalMin）
        val minIntervalMs = intervalMin * 60 * 1000L
        val recentCount = ServiceLocator.chatMessageDao.countOutboundSince(
            System.currentTimeMillis() - minIntervalMs
        )
        if (recentCount > 0) {
            Log.d(TAG, "距上次主动发起不足 $intervalMin 分钟，跳过")
            return
        }

        // 3. 概率判定
        if (Random.nextFloat() > probability) {
            Log.d(TAG, "概率判定未通过（p=$probability），跳过")
            return
        }

        // 4. 执行主动发起（PromptBuilder 已注入当前 state + activity，
        //    LLM 会基于当前场景生成贴切的主动消息）
        //    Admin v2 per_state_initiate.candidates 暂不直接喂给 LLM
        //    （避免和 PromptBuilder 的现有提示重复，让 LLM 自由生成）
        Log.d(TAG, "触发主动发起（state=${state.displayName}）")
        val interactor = ChatInteractor(context)
        interactor.agentInitiate()
    }

    fun stop() {
        checkJob?.cancel()
    }
}
