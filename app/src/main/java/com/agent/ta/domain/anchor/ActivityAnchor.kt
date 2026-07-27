package com.agent.ta.domain.anchor

import com.agent.ta.data.model.AgentState

/**
 * 活动锚点（Activity Anchor）
 *
 * 结构化的"Agent 当前正在做什么"，作为应用侧权威事实注入 system prompt，
 * 让 LLM 始终锚定真实活动状态，避免前后矛盾。
 *
 * 设计来源：参考 MochiBot 的"状态锚点"思想，但 MochiBot 没有此机制，
 * 这是本项目的差异化补足。
 *
 * 与 AgentState（normal/busy/idle/unavailable 宏观状态）的关系：
 * - AgentState 决定可回复性、延迟、主动频率
 * - ActivityAnchor 决定具体活动内容（如"健身"vs"洗澡"），用于回复一致性校验
 *
 * 两层来源：
 * 1. 作息表自动派生（SCHEDULE）：默认从当前时段的 activity 字段生成
 * 2. LLM 显式设置（LLM）：通过 set_activity 工具微调（如"提前洗完澡了"）
 *
 * 过期机制：
 * - expectedEnd 到达后自动回退到作息表当前时段
 * - LLM 设置的 anchor 有 durationMinutes，到期后自动失效
 *
 * 持久化：
 * - 序列化为 JSON 存入 SharedPreferences，App 重启后恢复
 * - 仅持久化 LLM 设置的 anchor（SCHEDULE 来源每次重新派生）
 */
data class ActivityAnchor(
    /** 当前活动内容（如"健身"、"洗澡"、"写代码"） */
    val activity: String,
    /** 对应的宏观状态 */
    val state: AgentState,
    /** 开始时间（epochMilli） */
    val startedAt: Long,
    /** 预计结束时间（epochMilli），到期后回退到作息表派生 */
    val expectedEnd: Long,
    /** 来源 */
    val source: AnchorSource,
    /** 作息表时段开始（HH:mm），仅 SCHEDULE 来源有值 */
    val slotStart: String = "",
    /** 作息表时段结束（HH:mm），仅 SCHEDULE 来源有值 */
    val slotEnd: String = ""
) {
    /**
     * 是否已过期（当前时间超过 expectedEnd）
     */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expectedEnd

    /**
     * 已进行时长（分钟）
     */
    fun elapsedMinutes(now: Long = System.currentTimeMillis()): Int {
        return ((now - startedAt) / 60_000).toInt().coerceAtLeast(0)
    }

    /**
     * 剩余时长（分钟），负数表示已过期
     */
    fun remainingMinutes(now: Long = System.currentTimeMillis()): Int {
        return ((expectedEnd - now) / 60_000).toInt()
    }

    /**
     * 进度描述（用于 prompt 注入，让 LLM 知道是刚开始/进行中/快结束）
     */
    fun progressDescription(now: Long = System.currentTimeMillis()): String {
        val elapsed = elapsedMinutes(now)
        val remaining = remainingMinutes(now)
        return when {
            remaining <= 0 -> "已超时"
            elapsed < 5 -> "刚开始 ${elapsed}分钟"
            remaining <= 5 -> "快结束了，还剩 ${remaining}分钟"
            else -> "已进行 ${elapsed}分钟"
        }
    }
}

/**
 * Anchor 来源
 */
enum class AnchorSource {
    /** 从作息表当前时段自动派生 */
    SCHEDULE,

    /** LLM 通过 set_activity 工具显式设置 */
    LLM,

    /** 从用户消息推断（预留，暂不实现） */
    INFERRED
}
