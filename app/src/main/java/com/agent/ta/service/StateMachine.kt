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

    private val _currentState = MutableStateFlow(AgentState.BORED)
    val currentState: StateFlow<AgentState> = _currentState

    // 当天作息 slots（由 DailyPlanner 生成，可被 ScheduleAdjuster 更新）
    private var dailySlots: List<DailySlot> = emptyList()

    // 回复延迟配置（从 AgentConfig.behavior.replyDelaySec 读取）
    private var replyDelayMap: Map<String, com.agent.ta.data.model.ReplyDelay> = emptyMap()

    /**
     * 初始化状态机
     *
     * @param slots 当天作息（由 DailyPlanner 生成）
     * @param delays 各状态的回复延迟配置
     */
    fun init(slots: List<DailySlot>, delays: Map<String, com.agent.ta.data.model.ReplyDelay>) {
        dailySlots = slots
        replyDelayMap = delays
        val newState = computeCurrentState()
        _currentState.value = newState
        Log.d(TAG, "状态机初始化：当前状态 = ${newState.displayName}，当天 ${slots.size} 个时段")

        scope.launch {
            stateLogDao.insert(
                com.agent.ta.data.local.entity.StateLogEntity(
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
     * 根据当前时间计算应该处于的状态
     */
    private fun computeCurrentState(): AgentState {
        return computeCurrentSlot()?.let { AgentState.fromId(it.state) } ?: AgentState.BORED
    }

    /**
     * 获取当前时段（包含 activity 具体活动描述）
     * 供 PromptBuilder 使用，让 LLM 知道当前具体在做什么
     */
    fun getCurrentSlot(): DailySlot? {
        return computeCurrentSlot()
    }

    /**
     * 获取今日全天作息（供 PromptBuilder 注入到 system prompt，让 Agent 知道接下来要做什么，不要瞎编）
     */
    fun getTodaySlots(): List<DailySlot> = dailySlots

    private fun computeCurrentSlot(): DailySlot? {
        if (dailySlots.isEmpty()) return null
        val now = java.time.ZonedDateTime.now(SCHEDULE_ZONE).toLocalTime()
        return dailySlots.firstOrNull { slot ->
            val start = LocalTime.parse(slot.start)
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
     * 切换状态（由调度器调用）
     */
    fun switchTo(state: AgentState) {
        if (_currentState.value == state) return
        Log.d(TAG, "状态切换：${_currentState.value.displayName} → ${state.displayName}")
        val now = System.currentTimeMillis()

        scope.launch {
            val latest = stateLogDao.getLatest()
            latest?.let { stateLogDao.updateExit(it.id, now) }
            stateLogDao.insert(
                com.agent.ta.data.local.entity.StateLogEntity(
                    state = state.id,
                    enteredAt = now
                )
            )
        }
        _currentState.value = state
    }

    /**
     * 当前状态是否可以回复用户消息
     */
    fun canReplyNow(): Boolean = when (_currentState.value) {
        AgentState.SLEEP, AgentState.BATH -> false
        AgentState.WORK, AgentState.GAME, AgentState.BORED, AgentState.HAPPY -> true
    }

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
                AgentState.WORK -> (60..300).random().toLong()
                AgentState.GAME -> (120..300).random().toLong()
                AgentState.BORED -> (1..10).random().toLong()
                AgentState.HAPPY -> (1..10).random().toLong()  // 开心状态快速回复
                AgentState.SLEEP, AgentState.BATH -> null
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
            val start = LocalTime.parse(slot.start)
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

        val nextState = AgentState.fromId(nextSlot.state) ?: AgentState.BORED
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
            val start = LocalTime.parse(slot.start)
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
            var switchTime = checkTime.with(LocalTime.parse(nextSlot.start))
            if (!switchTime.isAfter(checkTime)) {
                switchTime = switchTime.plusDays(1)
            }
            val nextState = AgentState.fromId(nextSlot.state) ?: AgentState.BORED
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
