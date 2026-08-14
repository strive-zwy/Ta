package com.agent.ta.service

import android.util.Log
import com.agent.ta.data.local.dao.StateLogDao
import com.agent.ta.data.model.AgentState
import com.agent.ta.data.model.DailySlot
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId

/**
 * 状态机核心
 *
 * 职责：
 * 1. 从 DailySchedule 读取当天作息并计算当前状态
 * 2. 维护状态切换日志
 * 3. 提供"当前是否可回复"判定
 * 4. 提供回复延迟时间
 *
 * 改造说明：
 * - 不再从 AgentConfig.schedule 读取固定作息
 * - 改为从 DailySchedule 表读取当天 LLM 生成的作息
 * - 支持运行时更新当天作息（Agent 自主调整）
 */
class StateMachine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLogDao: StateLogDao = ServiceLocator.stateLogDao

    private val _currentState = MutableStateFlow(AgentState.IDLE)
    val currentState: StateFlow<AgentState> = _currentState

    // 当天作息 slots（由 DailyPlanner 生成，可被 ScheduleAdjuster 更新）
    @Volatile
    private var dailySlots: List<DailySlot> = emptyList()

    // 回复延迟配置（从 AgentConfig.behavior.replyDelaySec 读取）
    @Volatile
    private var replyDelayMap: Map<String, com.agent.ta.data.model.ReplyDelay> = emptyMap()

    /** 切换前的时段（供 LifeEventInitiator 判断起床/睡觉等节点类型） */
    @Volatile
    private var prevSlot: DailySlot? = null

    /** 最近一次 init/switchTo 时的时段（用于下次切换时记录 prevSlot） */
    @Volatile
    private var lastSlot: DailySlot? = null

    /** 当前 Agent 实例 ID（多 Agent 数据隔离） */
    @Volatile
    private var agentId: Long = 0L

    /**
     * 初始化状态机
     *
     * @param slots 当天作息（由 DailyPlanner 生成）
     * @param delays 各状态的回复延迟配置
     * @param agentId 当前 Agent 实例 ID
     */
    fun init(slots: List<DailySlot>, delays: Map<String, com.agent.ta.data.model.ReplyDelay>, agentId: Long) {
        dailySlots = slots
        replyDelayMap = delays
        this.agentId = agentId
        val newState = computeCurrentState()
        _currentState.value = newState
        lastSlot = computeCurrentSlot()
        Log.d(TAG, "状态机初始化：当前状态 = ${newState.displayName}，当天 ${slots.size} 个时段")

        scope.launch {
            stateLogDao.insert(
                com.agent.ta.data.local.entity.StateLogEntity(
                    agentId = agentId,
                    state = newState.id,
                    enteredAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * 更新当天作息（Agent 自主调整后调用）
     */
    fun updateDailySlots(newSlots: List<DailySlot>) {
        Log.d(TAG, "作息已更新：${dailySlots.size} → ${newSlots.size} 个时段")
        dailySlots = newSlots
        // 重新计算当前状态
        val newState = computeCurrentState()
        if (newState != _currentState.value) {
            switchTo(newState)
        }
    }

    /**
     * 轻量级更新回复延迟配置（不重新生成作息）
     * 用于行为配置页保存后立即生效，避免调 LLM 重新生成作息的开销。
     */
    fun updateReplyDelays(delays: Map<String, com.agent.ta.data.model.ReplyDelay>) {
        replyDelayMap = delays
        Log.d(TAG, "回复延迟已更新：${delays.mapValues { it.value }}")
    }

    /**
     * 根据当前时间计算应该处于的状态
     *
     * Phase 1 分级睡眠：
     * - slot.sleepDepth=="light" → LIGHT_SLEEP（浅睡，可被消息吵醒回复）
     * - slot.sleepDepth=="deep" → UNAVAILABLE（深睡，不可回复）
     * - slot.sleepDepth==null → 沿用 slot.state 字段
     */
    private fun computeCurrentState(): AgentState {
        val slot = computeCurrentSlot() ?: return AgentState.IDLE
        return when (slot.sleepDepth) {
            "light" -> AgentState.LIGHT_SLEEP
            "deep" -> AgentState.UNAVAILABLE
            else -> AgentState.fromId(slot.state) ?: AgentState.IDLE
        }
    }

    /**
     * 获取当前时段（包含 activity 具体活动描述）
     * 供 PromptBuilder 使用，让 LLM 知道当前具体在做什么
     */
    fun getCurrentSlot(): DailySlot? {
        return computeCurrentSlot()
    }

    /**
     * 获取切换前的时段（供 LifeEventInitiator 判断起床/睡觉等节点类型）
     */
    fun getPrevSlot(): DailySlot? = prevSlot

    /**
     * 获取今日全天作息（供 PromptBuilder 注入到 system prompt，让 Agent 知道接下来要做什么，不要瞎编）
     */
    fun getTodaySlots(): List<DailySlot> = dailySlots

    private fun computeCurrentSlot(): DailySlot? {
        if (dailySlots.isEmpty()) return null
        val now = java.time.ZonedDateTime.now(SCHEDULE_ZONE).toLocalTime()
        return dailySlots.firstOrNull { slot ->
            val start = parseStartTime(slot.start)
            val end = parseEndTime(slot.end)
            if (start <= end) {
                now >= start && now < end
            } else {
                // 跨午夜
                now >= start || now < end
            }
        }
    }

    /**
     * 解析结束时间（处理 "24:00" 边界）
     */
    private fun parseEndTime(timeStr: String): LocalTime {
        return if (timeStr == "24:00" || timeStr == "24:00:00") {
            LocalTime.MIDNIGHT
        } else {
            LocalTime.parse(timeStr)
        }
    }

    /**
     * 解析开始时间（处理 "24:00" 边界）
     * "24:00" 作为开始时间非法，降级为 23:59:59 避免崩溃
     */
    private fun parseStartTime(timeStr: String): LocalTime {
        return if (timeStr == "24:00" || timeStr == "24:00:00") {
            LocalTime.of(23, 59, 59)
        } else {
            LocalTime.parse(timeStr)
        }
    }

    /**
     * 切换状态（由调度器调用）
     */
    fun switchTo(state: AgentState) {
        if (_currentState.value == state) return
        Log.d(TAG, "状态切换：${_currentState.value.displayName} → ${state.displayName}")
        val now = System.currentTimeMillis()

        // 记录切换前的时段（getCurrentSlot 在时段边界触发时已返回新 slot，
        // 故使用 lastSlot 作为切换前的时段，供 LifeEventInitiator 判断节点类型）
        prevSlot = lastSlot
        lastSlot = computeCurrentSlot()

        scope.launch {
            val latest = stateLogDao.getLatest(agentId)
            latest?.let { stateLogDao.updateExit(agentId, it.id, now) }
            stateLogDao.insert(
                com.agent.ta.data.local.entity.StateLogEntity(
                    agentId = agentId,
                    state = state.id,
                    enteredAt = now
                )
            )
        }
        _currentState.value = state
    }

    /**
     * 当前状态是否可以回复用户消息
     * UNAVAILABLE（深睡/洗澡等）不可回复，走待回复队列
     * LIGHT_SLEEP（浅睡）可回复，但迷糊慢延迟
     */
    fun canReplyNow(): Boolean = _currentState.value != AgentState.UNAVAILABLE

    /**
     * 获取当前状态的回复延迟（秒）
     * null 表示延迟到状态结束（defer）
     */
    fun getReplyDelaySec(): Long? {
        val state = _currentState.value
        val delay = replyDelayMap[state.id]
        return when (delay) {
            is com.agent.ta.data.model.ReplyDelay.Range -> (delay.min..delay.max).random().toLong()
            is com.agent.ta.data.model.ReplyDelay.Defer -> null
            null -> when (state) {
                AgentState.NORMAL -> (3..8).random().toLong()
                AgentState.BUSY -> (30..120).random().toLong()
                AgentState.IDLE -> (1..3).random().toLong()
                AgentState.UNAVAILABLE -> null
                AgentState.LIGHT_SLEEP -> (30..60).random().toLong()  // 迷糊慢回复
            }
        }
    }

    /**
     * 获取下一个状态切换点（epochMilli）
     * 用于 StateScheduler 注册 AlarmManager
     */
    fun getNextSwitchTime(zoneId: ZoneId = ZoneId.of("Asia/Shanghai")): Pair<Long, AgentState>? {
        if (dailySlots.isEmpty()) return null

        val now = java.time.ZonedDateTime.now(zoneId)
        val nowTime = now.toLocalTime()

        // 找到当前所在 slot 的下一个 slot
        val currentIdx = dailySlots.indexOfFirst { slot ->
            val start = parseStartTime(slot.start)
            val end = parseEndTime(slot.end)
            if (start <= end) {
                nowTime >= start && nowTime < end
            } else {
                nowTime >= start || nowTime < end
            }
        }

        if (currentIdx == -1) return null

        val nextIdx = (currentIdx + 1) % dailySlots.size
        val nextSlot = dailySlots[nextIdx]
        var switchTime = now.with(LocalTime.parse(nextSlot.start))
        if (!switchTime.isAfter(now)) {
            switchTime = switchTime.plusDays(1)
        }

        val nextState = AgentState.fromId(nextSlot.state) ?: AgentState.IDLE
        return Pair(switchTime.toInstant().toEpochMilli(), nextState)
    }

    /**
     * 获取未来 N 个状态切换点
     */
    fun getUpcomingSwitches(
        count: Int = 8,
        zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
    ): List<Pair<Long, AgentState>> {
        if (dailySlots.isEmpty()) return emptyList()

        val now = java.time.ZonedDateTime.now(zoneId)
        val nowTime = now.toLocalTime()
        val result = mutableListOf<Pair<Long, AgentState>>()

        // 找到当前所在 slot
        var currentIdx = dailySlots.indexOfFirst { slot ->
            val start = parseStartTime(slot.start)
            val end = parseEndTime(slot.end)
            if (start <= end) {
                nowTime >= start && nowTime < end
            } else {
                nowTime >= start || nowTime < end
            }
        }
        if (currentIdx == -1) currentIdx = 0

        var checkTime = now
        for (i in 0 until count.coerceAtMost(dailySlots.size)) {
            val nextIdx = (currentIdx + 1 + i) % dailySlots.size
            val nextSlot = dailySlots[nextIdx]
            var switchTime = checkTime.with(parseStartTime(nextSlot.start))
            if (!switchTime.isAfter(checkTime)) {
                switchTime = switchTime.plusDays(1)
            }
            val nextState = AgentState.fromId(nextSlot.state) ?: AgentState.IDLE
            result.add(Pair(switchTime.toInstant().toEpochMilli(), nextState))
            checkTime = switchTime
        }

        return result
    }

    companion object {
        private const val TAG = "StateMachine"
        private val SCHEDULE_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
