package com.agent.ta.infrastructure.observer

import com.agent.ta.di.ServiceLocator
import com.agent.ta.infrastructure.time.TimeContext

/**
 * 近期对话观察者（L0 基础设施层）
 *
 * 职责：
 * 1. 监控用户长时间未响应（如超过 30 分钟无消息）
 * 2. 提供最近一条用户消息的时间和内容摘要
 * 3. 触发关怀消息评估（由 ThinkActDecider 决定是否发起）
 *
 * hasDelta 判定：
 * - "用户是否长时间未响应"状态变化（true → false 或 false → true）
 * - 最近一条用户消息变化（新消息到达）
 *
 * 关键设计：
 * - 不直接发起关怀消息，仅标记状态变化
 * - 由 Heartbeat 收到 hasDelta=true 后调用 ThinkActDecider 评估
 * - ThinkActDecider 综合判断是否真的发起（避免骚扰）
 */
class RecentConversationObserver : Observer {

    override val id: String = "recent_conversation"

    private val timeContext = TimeContext.getInstance()

    /** 用户长时间未响应阈值（毫秒） */
    private val silenceThresholdMs = 30L * 60 * 1000  // 30 分钟

    override suspend fun collect(): ObserverSnapshot {
        val timestamp = timeContext.nowMillis()

        // 查询最近一条用户消息（使用专用查询，避免全表扫描）
        val lastUserMessage = ServiceLocator.chatMessageDao.getLastInboundMessage()

        val userMessageTime = lastUserMessage?.createdAt ?: 0L
        val userMessagePreview = lastUserMessage?.text?.take(50) ?: ""

        val isUserSilent = if (userMessageTime > 0) {
            (timestamp - userMessageTime) > silenceThresholdMs
        } else {
            false  // 从未发过消息，不算"长时间未响应"
        }

        val silenceMinutes = if (userMessageTime > 0) {
            (timestamp - userMessageTime) / 60_000
        } else {
            0L
        }

        val promptHint = buildString {
            appendLine("【近期对话观察】")
            if (userMessageTime > 0) {
                appendLine("最近用户消息：${timeContext.formatTime(userMessageTime)} \"${userMessagePreview}\"")
                appendLine("距上次用户消息：${silenceMinutes}分钟")
                if (isUserSilent) {
                    appendLine("状态：用户长时间未响应（可能不在手机旁）")
                } else {
                    appendLine("状态：用户在线活跃中")
                }
            } else {
                appendLine("状态：用户从未发过消息")
            }
        }

        return ObserverSnapshot(
            observerId = id,
            timestamp = timestamp,
            data = mapOf(
                "last_user_message_time" to userMessageTime,
                "last_user_message_preview" to userMessagePreview,
                "is_user_silent" to isUserSilent,
                "silence_minutes" to silenceMinutes
            ),
            promptHint = promptHint
        )
    }

    override fun hasDelta(current: ObserverSnapshot, previous: ObserverSnapshot?): Boolean {
        if (previous == null) return true

        val currentSilent = current.data["is_user_silent"] as? Boolean ?: false
        val currentUserTime = current.data["last_user_message_time"] as? Long ?: 0L
        val prevSilent = previous.data["is_user_silent"] as? Boolean ?: false
        val prevUserTime = previous.data["last_user_message_time"] as? Long ?: 0L

        // 沉默状态变化（false → true 或 true → false）
        val silentStateChanged = currentSilent != prevSilent

        // 用户发了新消息
        val newMessageArrived = currentUserTime != prevUserTime

        return silentStateChanged || newMessageArrived
    }
}
