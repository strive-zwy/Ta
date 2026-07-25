package com.agent.ta.domain

import android.util.Log
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.data.model.DailySlot
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 作息调整器
 *
 * 职责：让 Agent 可以随时自主调整后续作息
 *
 * 触发场景：
 * 1. 用户想和 Agent 聊天，但 Agent 当前是 sleep/bath 状态
 *    → Agent "自己决定"推迟睡觉或延长空闲时间
 * 2. Agent 当前无聊，且对话很愉快
 *    → Agent "自己决定"延长无聊状态，不急着去工作/游戏
 * 3. 用户明确表达想让 Agent 陪
 *    → Agent 主动调整后续时段
 *
 * 实现：调用 LLM，传入当前作息 + 调整原因，让 LLM 输出新的后续作息
 */
class ScheduleAdjuster {

    private val dailyPlanner = DailyPlanner()
    private val dailyScheduleDao = ServiceLocator.dailyScheduleDao

    /**
     * 调整当天作息
     *
     * @param config Agent 配置
     * @param reason 调整原因（如"用户想继续聊天，推迟睡觉"）
     * @return 新的当天作息
     */
    suspend fun adjustTodaySchedule(
        config: AgentConfig,
        reason: String,
        zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
    ): List<DailySlot> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Agent 自主调整作息：$reason")
            val newSlots = dailyPlanner.regenerateTodaySchedule(config, zoneId, reason)
            Log.d(TAG, "作息已调整，新时段数：${newSlots.size}")
            newSlots
        } catch (e: Exception) {
            Log.e(TAG, "调整作息失败", e)
            emptyList()
        }
    }

    /**
     * 判定是否应该触发作息调整
     *
     * @param currentState 当前状态
     * @param userMessageContent 用户消息内容
     * @return 调整原因（空字符串表示不调整）
     */
    fun shouldAdjust(currentState: com.agent.ta.data.model.AgentState, userMessageContent: String): String {
        val content = userMessageContent.lowercase()

        // 无法回复状态下用户发消息 → Agent 可能决定"起来聊会天"
        if (currentState == com.agent.ta.data.model.AgentState.UNAVAILABLE) {
            if (containsAny(content, listOf("聊", "陪", "想", "睡不着", "急", "帮忙", "在吗", "睡了吗"))) {
                return "用户想聊天，我决定从休息状态起来陪一会儿"
            }
        }

        // 忙碌状态下，用户表达想让 Agent 陪
        if (currentState == com.agent.ta.data.model.AgentState.BUSY) {
            if (containsAny(content, listOf("陪我", "别工作", "别玩", "过来", "想聊"))) {
                return "用户希望我陪他，我决定放下手头的事"
            }
        }

        return ""
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it) }
    }

    companion object {
        private const val TAG = "ScheduleAdjuster"
    }
}
