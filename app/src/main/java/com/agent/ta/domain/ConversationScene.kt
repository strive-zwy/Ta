package com.agent.ta.domain

/**
 * 对话场景枚举（Task 12）
 *
 * 区分首次见面与常规对话，让 PromptBuilder 注入对应的场景引导。
 * ChatInteractor 在生成回复时根据 FirstMeetingCoordinator 的状态判断当前场景。
 *
 * - NORMAL：常规对话（用户发消息 / 主动发起 / 补回复 / 配置模式）
 * - FIRST_MEETING_GREETING：首次见面主动问候（Agent 主动发起，必须自我介绍 + 询问称呼）
 * - FIRST_MEETING_REPLY：用户先发消息触发的首次见面回复（合并为首次见面场景，不补发突兀问候）
 */
enum class ConversationScene {
    NORMAL,
    FIRST_MEETING_GREETING,
    FIRST_MEETING_REPLY;

    val isFirstMeeting: Boolean
        get() = this == FIRST_MEETING_GREETING || this == FIRST_MEETING_REPLY
}
