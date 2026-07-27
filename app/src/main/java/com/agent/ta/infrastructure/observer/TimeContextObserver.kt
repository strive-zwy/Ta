package com.agent.ta.infrastructure.observer

import com.agent.ta.data.model.AgentState
import com.agent.ta.di.ServiceLocator
import com.agent.ta.infrastructure.time.TimeContext
import com.agent.ta.service.AgentEngine

/**
 * 时间上下文观察者（L0 基础设施层）
 *
 * 职责：
 * 1. 监控时段切换（如 14:00 工作 → 17:30 休息）
 * 2. 监控跨天切换（作息表日期变化）
 * 3. 提供 promptHint 包含当前时段和下一时段信息
 *
 * hasDelta 判定：
 * - 当前时段变化（slotStart 不同）
 * - 跨天（todayDateString 变化）
 *
 * 注意：本观察者只读取数据，不触发 StateMachine 切换
 * StateMachine 切换由 StateScheduler 调度，本观察者仅用于让 LLM 感知时段边界
 */
class TimeContextObserver : Observer {

    override val id: String = "time_context"

    private val timeContext = TimeContext.getInstance()

    private var lastSlotStart: String = ""
    private var lastDateString: String = ""

    override suspend fun collect(): ObserverSnapshot {
        val timestamp = timeContext.nowMillis()
        val now = timeContext.now()
        val todaySchedule = AgentEngine.getTodaySchedule()
        val currentState = AgentEngine.currentState.value

        // 找到当前时段和下一时段
        val currentSlot = AgentEngine.getCurrentSlot()
        val nextSlot = findNextSlot(todaySchedule, currentSlot?.start)

        val promptHint = buildString {
            appendLine("【时间上下文观察】")
            appendLine("当前时间：${timeContext.formatDateTime(timestamp)}")
            if (currentSlot != null) {
                appendLine("当前时段：${currentSlot.start}-${currentSlot.end}（${currentSlot.activity}，状态=${currentState.displayName}）")
            }
            if (nextSlot != null) {
                appendLine("下一时段：${nextSlot.start}-${nextSlot.end}（${nextSlot.activity}）")
            }
        }

        return ObserverSnapshot(
            observerId = id,
            timestamp = timestamp,
            data = mapOf(
                "current_time" to timeContext.formatDateTime(timestamp),
                "current_slot_start" to (currentSlot?.start ?: ""),
                "current_slot_end" to (currentSlot?.end ?: ""),
                "current_activity" to (currentSlot?.activity ?: ""),
                "next_slot_start" to (nextSlot?.start ?: ""),
                "next_slot_activity" to (nextSlot?.activity ?: ""),
                "today_date" to timeContext.todayDateString()
            ),
            promptHint = promptHint
        )
    }

    override fun hasDelta(current: ObserverSnapshot, previous: ObserverSnapshot?): Boolean {
        if (previous == null) return true

        val currentSlotStart = current.data["current_slot_start"] as? String ?: ""
        val currentDate = current.data["today_date"] as? String ?: ""

        val slotChanged = currentSlotStart != lastSlotStart
        val dayChanged = currentDate != lastDateString

        return slotChanged || dayChanged
    }

    /**
     * 找下一个时段
     */
    private fun findNextSlot(
        schedule: List<com.agent.ta.data.model.DailySlot>,
        currentSlotStart: String?
    ): com.agent.ta.data.model.DailySlot? {
        if (schedule.isEmpty() || currentSlotStart == null) return null

        val currentIdx = schedule.indexOfFirst { it.start == currentSlotStart }
        if (currentIdx < 0) return null

        val nextIdx = (currentIdx + 1) % schedule.size
        return schedule[nextIdx]
    }
}
