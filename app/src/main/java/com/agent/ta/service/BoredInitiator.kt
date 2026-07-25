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
import kotlin.random.Random

/**
 * 主动发起判定器（Admin v3 极简版）
 *
 * 每个可回复状态按 frequencyPerDay（每天发起次数）控制主动发消息频率：
 * - IDLE 时按较高频次发起话题（主要发起场景）
 * - NORMAL 时偶尔主动分享/关心
 * - BUSY 时偶尔吐槽"好累啊" / "想摸鱼"
 * - UNAVAILABLE 不主动发
 *
 * 频次换算：frequencyPerDay=N → 每5分钟检查概率 = N/(24*12)
 * 例：idle=8次/天 → 概率 8/288 ≈ 0.028
 *
 * 规则：
 * - 每 5 分钟判定一次
 * - 最近 30 分钟内已发过则跳过（固定冷却，避免短时间连发）
 * - 23:00-08:00 静音时段不主动发
 */
class BoredInitiator(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checkJob: Job? = null

    private val prefs = ServiceLocator.userPreferences

    companion object {
        private const val TAG = "BoredInitiator"
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L  // 5 分钟
        private const val COOLDOWN_MS = 30 * 60 * 1000L       // 固定冷却 30 分钟
        private val SILENT_START = 23
        private val SILENT_END = 8
    }

    /**
     * 取某状态的主动发起配置
     * 返回 Pair(enabled, probability)
     */
    private fun resolveInitiateConfig(state: AgentState): Pair<Boolean, Float> {
        val config = ServiceLocator.agentConfigProvider.get()
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
     * 判定并执行主动发起
     */
    private suspend fun checkAndInitiate() {
        val state = AgentEngine.currentState.value
        val (enabled, probability) = resolveInitiateConfig(state)
        if (!enabled || probability <= 0f) return

        // 1. 静音时段检查
        val hour = java.time.LocalTime.now().hour
        if (hour >= SILENT_START || hour < SILENT_END) {
            Log.d(TAG, "静音时段，跳过")
            return
        }

        // 2. 冷却检查（固定 30 分钟）
        val recentCount = ServiceLocator.chatMessageDao.countOutboundSince(
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

        // 4. 执行主动发起
        Log.d(TAG, "触发主动发起（state=${state.displayName}, p=$probability）")
        val interactor = ChatInteractor(context)
        interactor.agentInitiate()
    }

    fun stop() {
        checkJob?.cancel()
    }
}
