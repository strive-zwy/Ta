package com.agent.ta.data.model

/**
 * Agent 状态枚举
 *
 * 按能否回复 + 回复积极性分为 4 种状态：
 * - NORMAL：日常状态，可回复，正常延迟
 * - BUSY：忙碌状态，可回复但慢，长延迟
 * - IDLE：空闲状态，可回复且快，短延迟，更易主动发起
 * - UNAVAILABLE：无法回复（睡觉/洗澡等），走待回复队列
 *
 * 情绪（neutral/happy/calm）由 LLM 根据上下文在每条回复中自主判断，
 * 与状态解耦——状态控制行为逻辑，情绪控制语音表现。
 */
enum class AgentState(val id: String, val displayName: String) {
    NORMAL("normal", "正常"),
    BUSY("busy", "忙碌"),
    IDLE("idle", "空闲"),
    UNAVAILABLE("unavailable", "无法回复");

    companion object {
        /**
         * 从字符串 id 解析状态，兼容旧状态值映射：
         * - default / "" / bored → IDLE
         * - happy / neutral → NORMAL
         * - work / game → BUSY
         * - sleep / bath → UNAVAILABLE
         */
        fun fromId(id: String): AgentState? = when (id.lowercase().trim()) {
            "default", "", "bored" -> IDLE
            "happy", "neutral" -> NORMAL
            "work", "game" -> BUSY
            "sleep", "bath" -> UNAVAILABLE
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
