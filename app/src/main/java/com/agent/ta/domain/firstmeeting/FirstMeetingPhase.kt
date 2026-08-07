package com.agent.ta.domain.firstmeeting

/**
 * 首次见面状态机的阶段枚举
 *
 * 流转规则：
 * - NOT_STARTED → GREETING_IN_PROGRESS（beginGreeting 抢占）
 * - GREETING_IN_PROGRESS → WAITING_NICKNAME（问候成功入库）
 * - GREETING_IN_PROGRESS → NOT_STARTED（LLM 失败回退；TTS 失败不回退）
 * - WAITING_NICKNAME → COMPLETED_WITH_NICKNAME（用户明确给出称呼）
 * - WAITING_NICKNAME → FOLLOW_UP_ASKED（第一次未识别称呼，追问一次）
 * - WAITING_NICKNAME → COMPLETED_WITHOUT_NICKNAME（用户明确拒绝）
 * - FOLLOW_UP_ASKED → COMPLETED_WITH_NICKNAME（追问后用户给出称呼）
 * - FOLLOW_UP_ASKED → COMPLETED_WITHOUT_NICKNAME（追问后仍不明确或拒绝）
 * - COMPLETED_WITH_NICKNAME / COMPLETED_WITHOUT_NICKNAME 为终态，不再流转
 */
enum class FirstMeetingPhase(val id: String) {
    NOT_STARTED("NOT_STARTED"),
    GREETING_IN_PROGRESS("GREETING_IN_PROGRESS"),
    WAITING_NICKNAME("WAITING_NICKNAME"),
    FOLLOW_UP_ASKED("FOLLOW_UP_ASKED"),
    COMPLETED_WITH_NICKNAME("COMPLETED_WITH_NICKNAME"),
    COMPLETED_WITHOUT_NICKNAME("COMPLETED_WITHOUT_NICKNAME");

    val isCompleted: Boolean
        get() = this == COMPLETED_WITH_NICKNAME || this == COMPLETED_WITHOUT_NICKNAME

    val canBeginGreeting: Boolean
        get() = this == NOT_STARTED

    val isAwaitingNickname: Boolean
        get() = this == WAITING_NICKNAME || this == FOLLOW_UP_ASKED

    companion object {
        fun fromId(id: String): FirstMeetingPhase? = entries.find { it.id == id }
    }
}
