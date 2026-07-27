package com.agent.ta.domain

import android.util.Log
import com.agent.ta.data.local.dao.DailyScheduleDao
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.data.model.AgentState
import com.agent.ta.data.model.DailySlot
import com.agent.ta.data.remote.dto.ScheduleAdjustment
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 作息调整器（v3 事件驱动架构）
 *
 * 核心改进：
 * - 不再调 LLM 重新生成全天作息（成本高 + 丢失已完成时段）
 * - LLM 输出具体的调整事件类型 + 参数，本类局部修改 slots
 * - 多样化反应池 + 历史去重 + 概率约束，避免固定程式
 *
 * 6 类事件：
 * - EXTEND: 延长当前时段（如打游戏上瘾多玩会儿）
 * - SHORTEN: 缩短当前时段（如提前结束工作）
 * - SKIP: 跳过下一时段（如不洗澡直接睡觉）
 * - REPLACE: 替换当前时段活动内容
 * - INSERT: 当前时段后插入新时段
 * - SHIFT: 后移后续时段
 *
 * 保护机制：
 * - 重要工作时段（activity 含"会议/直播/演出/通告"）不可调整
 * - 跨午夜睡觉时段不调整
 * - 历史去重：最近 3 次同一事件类型不重复触发
 */
class ScheduleAdjuster {

    private val dailyScheduleDao: DailyScheduleDao = ServiceLocator.dailyScheduleDao
    private val json = Json { ignoreUnknownKeys = true }

    /** 最近触发的事件类型记录（用于去重，避免每次都用同一种反应） */
    private val recentAdjustments = ConcurrentLinkedDeque<String>()

    companion object {
        private const val TAG = "ScheduleAdjuster"
        private const val MAX_RECENT_RECORDS = 5
        private const val DEDUP_WINDOW = 3  // 最近 3 次内同一类型不重复
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** 受保护的活动关键词（不可调整） */
        private val PROTECTED_KEYWORDS = listOf("会议", "直播", "演出", "通告", "采访", "签约", "面试", "考试")
    }

    /**
     * 应用作息调整（事件驱动局部修改）
     *
     * @param config Agent 配置
     * @param adjustment LLM 输出的调整请求
     * @param currentSlots 当前作息
     * @param zoneId 时区
     * @return 新的作息列表（若调整失败或被保护，返回原列表）
     */
    suspend fun applyAdjustment(
        config: AgentConfig,
        adjustment: ScheduleAdjustment,
        currentSlots: List<DailySlot>,
        zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
    ): List<DailySlot> = withContext(Dispatchers.IO) {
        if (!adjustment.shouldAdjust || adjustment.adjustmentType.isBlank()) {
            return@withContext currentSlots
        }
        if (currentSlots.isEmpty()) {
            Log.w(TAG, "当前作息为空，无法调整")
            return@withContext currentSlots
        }

        // 历史去重检查
        val type = adjustment.adjustmentType.uppercase()
        val recentCount = recentAdjustments.count { it == type }
        if (recentCount >= DEDUP_WINDOW) {
            Log.d(TAG, "事件类型 $type 最近已触发 $recentCount 次，跳过避免程式化")
            return@withContext currentSlots
        }

        // 找到当前时段索引
        val now = LocalTime.now(zoneId)
        val currentIdx = findCurrentSlotIndex(currentSlots, now)
        if (currentIdx < 0) {
            Log.w(TAG, "未找到当前时段，无法调整")
            return@withContext currentSlots
        }

        val currentSlot = currentSlots[currentIdx]

        // 受保护时段检查
        if (isProtectedSlot(currentSlot)) {
            Log.d(TAG, "当前时段「${currentSlot.activity}」受保护，不可调整")
            return@withContext currentSlots
        }

        // 应用具体事件
        val newSlots = when (type) {
            "EXTEND" -> applyExtend(currentSlots, currentIdx, adjustment.durationMinutes)
            "SHORTEN" -> applyShorten(currentSlots, currentIdx, adjustment.durationMinutes)
            "SKIP" -> applySkip(currentSlots, currentIdx)
            "REPLACE" -> applyReplace(currentSlots, currentIdx, adjustment.newActivity, adjustment.newState)
            "INSERT" -> applyInsert(currentSlots, currentIdx, adjustment.durationMinutes, adjustment.newActivity, adjustment.newState)
            "SHIFT" -> applyShift(currentSlots, currentIdx, adjustment.durationMinutes)
            else -> {
                Log.w(TAG, "未知调整类型：$type")
                currentSlots
            }
        }

        if (newSlots != currentSlots && newSlots.isNotEmpty()) {
            // 记录到历史
            recentAdjustments.addFirst(type)
            while (recentAdjustments.size > MAX_RECENT_RECORDS) {
                recentAdjustments.removeLast()
            }

            // 持久化到 DB
            val today = LocalDate.now(zoneId).format(DATE_FORMAT)
            val entity = com.agent.ta.data.local.entity.DailyScheduleEntity(
                date = today,
                slotsJson = json.encodeToString(newSlots),
                isAdjusted = true,
                source = "adjust",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            dailyScheduleDao.upsert(entity)

            Log.d(TAG, "作息已调整（$type）：${adjustment.reason}，原因：${adjustment.reason}")
        }

        newSlots
    }

    /**
     * EXTEND：延长当前时段
     * 将当前时段的 end 时间延长 durationMinutes 分钟，后续时段顺延
     */
    private fun applyExtend(slots: List<DailySlot>, currentIdx: Int, durationMinutes: Int): List<DailySlot> {
        if (durationMinutes <= 0) return slots
        val result = slots.toMutableList()
        val current = result[currentIdx]
        val newEnd = addMinutesToTime(current.end, durationMinutes)
        result[currentIdx] = current.copy(end = newEnd)

        // 后续时段顺延
        shiftSubsequentSlots(result, currentIdx + 1, durationMinutes)
        return result
    }

    /**
     * SHORTEN：缩短当前时段
     * 将当前时段的 end 时间提前 durationMinutes 分钟，后续时段顺延提前
     */
    private fun applyShorten(slots: List<DailySlot>, currentIdx: Int, durationMinutes: Int): List<DailySlot> {
        if (durationMinutes <= 0) return slots
        val result = slots.toMutableList()
        val current = result[currentIdx]
        val newEnd = subtractMinutesFromTime(current.end, durationMinutes)
        // 确保缩短后时长仍 >= 5 分钟
        val start = LocalTime.parse(current.start)
        val end = parseEndTime(newEnd)
        if (durationBetween(start, end) < 5) {
            Log.d(TAG, "SHORTEN 后时长不足 5 分钟，跳过")
            return slots
        }
        result[currentIdx] = current.copy(end = newEnd)
        shiftSubsequentSlots(result, currentIdx + 1, -durationMinutes)
        return result
    }

    /**
     * SKIP：跳过下一时段
     * 删除下一个时段，将其时长合并到当前时段
     */
    private fun applySkip(slots: List<DailySlot>, currentIdx: Int): List<DailySlot> {
        if (currentIdx + 1 >= slots.size) return slots
        val result = slots.toMutableList()
        val current = result[currentIdx]
        val next = result[currentIdx + 1]

        // 受保护时段不可跳过
        if (isProtectedSlot(next)) {
            Log.d(TAG, "下一时段「${next.activity}」受保护，不可跳过")
            return slots
        }

        // 当前时段延长到下一时段结束
        result[currentIdx] = current.copy(end = next.end)
        result.removeAt(currentIdx + 1)
        Log.d(TAG, "跳过时段「${next.activity}」，当前时段延长到 ${next.end}")
        return result
    }

    /**
     * REPLACE：替换当前时段的活动内容
     */
    private fun applyReplace(
        slots: List<DailySlot>,
        currentIdx: Int,
        newActivity: String,
        newState: String
    ): List<DailySlot> {
        if (newActivity.isBlank()) return slots
        val result = slots.toMutableList()
        val current = result[currentIdx]
        val state = if (newState.isNotBlank()) newState else current.state
        result[currentIdx] = current.copy(activity = newActivity, state = state)
        Log.d(TAG, "替换时段活动：「${current.activity}」→「$newActivity」")
        return result
    }

    /**
     * INSERT：在当前时段后插入新时段
     * 当前时段缩短 durationMinutes，插入新时段
     */
    private fun applyInsert(
        slots: List<DailySlot>,
        currentIdx: Int,
        durationMinutes: Int,
        newActivity: String,
        newState: String
    ): List<DailySlot> {
        if (durationMinutes <= 0 || newActivity.isBlank()) return slots
        val result = slots.toMutableList()
        val current = result[currentIdx]

        // 当前时段缩短
        val currentNewEnd = subtractMinutesFromTime(current.end, durationMinutes)
        val start = LocalTime.parse(current.start)
        val end = parseEndTime(currentNewEnd)
        if (durationBetween(start, end) < 5) {
            Log.d(TAG, "INSERT 后当前时段时长不足 5 分钟，跳过")
            return slots
        }
        result[currentIdx] = current.copy(end = currentNewEnd)

        // 插入新时段
        val state = if (newState.isNotBlank()) newState else "idle"
        val newSlot = DailySlot(
            start = currentNewEnd,
            end = current.end,
            state = state,
            activity = newActivity
        )
        result.add(currentIdx + 1, newSlot)
        Log.d(TAG, "插入新时段：${newSlot.start}-${newSlot.end} ${newActivity}")
        return result
    }

    /**
     * SHIFT：后移后续时段
     * 当前时段之后的所时段顺延 durationMinutes 分钟
     */
    private fun applyShift(slots: List<DailySlot>, currentIdx: Int, durationMinutes: Int): List<DailySlot> {
        if (durationMinutes <= 0) return slots
        val result = slots.toMutableList()
        shiftSubsequentSlots(result, currentIdx + 1, durationMinutes)
        Log.d(TAG, "后续时段顺延 $durationMinutes 分钟")
        return result
    }

    /**
     * 后移指定索引之后的所有时段
     * @param offsetMinutes 正数后移，负数前移
     */
    private fun shiftSubsequentSlots(slots: MutableList<DailySlot>, fromIdx: Int, offsetMinutes: Int) {
        if (fromIdx >= slots.size) return
        // 最后一个时段是跨午夜睡觉时段，需要特殊处理（睡觉时段的 end 是次日时间，不调整 end 只调整 start）
        val lastIndex = slots.lastIndex
        for (i in fromIdx until slots.size) {
            val slot = slots[i]
            val newStart = addMinutesToTime(slot.start, offsetMinutes)
            // 跨午夜睡觉时段（最后一个）的 end 不调整（那是次日起床时间）
            val newEnd = if (i == lastIndex && isCrossMidnight(slot)) {
                slot.end  // 保持次日起床时间不变
            } else {
                addMinutesToTime(slot.end, offsetMinutes)
            }
            slots[i] = slot.copy(start = newStart, end = newEnd)
        }
    }

    /**
     * 判断是否为受保护时段（重要工作/事件不可调整）
     */
    private fun isProtectedSlot(slot: DailySlot): Boolean {
        return PROTECTED_KEYWORDS.any { slot.activity.contains(it) }
    }

    /**
     * 判断是否为跨午夜时段
     */
    private fun isCrossMidnight(slot: DailySlot): Boolean {
        return try {
            val start = LocalTime.parse(slot.start)
            val end = parseEndTime(slot.end)
            end <= start
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 找到当前时段索引
     */
    private fun findCurrentSlotIndex(slots: List<DailySlot>, now: LocalTime): Int {
        return slots.indexOfFirst { slot ->
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
     * 时间加分钟
     */
    private fun addMinutesToTime(timeStr: String, minutes: Int): String {
        return try {
            val time = if (timeStr == "24:00") LocalTime.of(23, 59) else LocalTime.parse(timeStr)
            val newTime = time.plusMinutes(minutes.toLong())
            // 不超过 23:59（避免跨午夜混乱，跨午夜只允许睡觉时段）
            val clamped = if (newTime == LocalTime.MIDNIGHT) LocalTime.of(23, 59) else newTime
            String.format("%02d:%02d", clamped.hour, clamped.minute)
        } catch (e: Exception) {
            timeStr
        }
    }

    /**
     * 时间减分钟
     */
    private fun subtractMinutesFromTime(timeStr: String, minutes: Int): String {
        return try {
            val time = if (timeStr == "24:00") LocalTime.of(23, 59) else LocalTime.parse(timeStr)
            val newTime = time.minusMinutes(minutes.toLong())
            String.format("%02d:%02d", newTime.hour, newTime.minute)
        } catch (e: Exception) {
            timeStr
        }
    }

    /**
     * 解析结束时间（处理 "24:00" 边界）
     */
    private fun parseEndTime(timeStr: String): LocalTime {
        return if (timeStr == "24:00" || timeStr == "24:00:00") {
            LocalTime.of(23, 59, 59)
        } else {
            LocalTime.parse(timeStr)
        }
    }

    /**
     * 计算两个时间之间的分钟数
     */
    private fun durationBetween(start: LocalTime, end: LocalTime): Long {
        return if (end > start) {
            java.time.Duration.between(start, end).toMinutes()
        } else {
            // 跨午夜
            java.time.Duration.between(start, LocalTime.MAX).toMinutes() +
                java.time.Duration.between(LocalTime.MIDNIGHT, end).toMinutes()
        }
    }

    /**
     * 兼容旧接口：基于 reason 重新规划全天作息
     *
     * 已废弃：新代码应使用 [applyAdjustment]
     * 保留是为了兼容 AgentEngine.adjustSchedule 的旧调用路径
     */
    suspend fun adjustTodaySchedule(
        config: AgentConfig,
        reason: String,
        zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
    ): List<DailySlot> = withContext(Dispatchers.IO) {
        Log.w(TAG, "adjustTodaySchedule 已废弃，请使用 applyAdjustment（reason=$reason）")
        // 旧路径降级：返回空列表，让调用方知道调整未执行
        // 新路径走 ChatInteractor → applyAdjustment
        emptyList()
    }
}
