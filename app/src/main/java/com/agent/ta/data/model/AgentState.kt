package com.agent.ta.data.model

/**
 * Agent 状态枚举
 *
 * 与 Admin 端 avatar.Mood / behavior.ValidStates 对齐：
 * - default: 兜底状态（Admin v2 新增，App 端映射为 BORED 行为）
 * - happy: 开心（Admin v2 新增，App 端按 GAME 处理回复延迟和主动发起）
 *
 * 注意：App 端不直接使用 DEFAULT/HAPPY 作为状态机状态，
 * 但 AgentConfig.avatars 的 mood 字段可能取这些值，
 * AvatarResolver 会把 "default" 映射为兜底头像。
 */
enum class AgentState(val id: String, val displayName: String) {
    SLEEP("sleep", "睡觉"),
    WORK("work", "工作"),
    GAME("game", "游戏"),
    BATH("bath", "洗澡"),
    BORED("bored", "无聊"),
    HAPPY("happy", "开心");

    companion object {
        /**
         * 从字符串 id 解析状态
         * - "default" 或空字符串回退为 BORED（兜底）
         * - 其他值精确匹配
         */
        fun fromId(id: String): AgentState? = when (id.lowercase().trim()) {
            "default", "" -> BORED
            else -> entries.find { it.id == id.lowercase().trim() }
        }
    }
}

/**
 * 消息方向
 */
enum class MessageDirection(val id: String) {
    INBOUND("inbound"),       // 用户发给 Agent
    OUTBOUND("outbound");     // Agent 发给用户

    companion object {
        fun fromId(id: String): MessageDirection? = entries.find { it.id == id }
    }
}

/**
 * 消息状态
 */
enum class MessageStatus(val id: String) {
    RECEIVED("received"),     // 收到用户消息
    PENDING("pending"),       // 待回复（入队等待）
    REPLIED("replied"),       // 已回复
    SENT("sent");             // 已发送（Agent 主动发）

    companion object {
        fun fromId(id: String): MessageStatus? = entries.find { it.id == id }
    }
}

