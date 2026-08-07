package com.agent.ta.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 首次见面状态实体（每个 Agent 一条记录，主键为 agentId）
 *
 * 记录 Agent 与用户首次见面的进度，驱动按人格主动问候、称呼提取和一次自然追问。
 *
 * phase 取值（FirstMeetingPhase）：
 * - NOT_STARTED：未开始（新导入 / 升级后无聊天记录的 Agent）
 * - GREETING_IN_PROGRESS：问候生成中（LLM/TTS 流程进行中）
 * - WAITING_NICKNAME：问候已发送，等待用户回复以提取称呼
 * - FOLLOW_UP_ASKED：第一次未识别称呼，已追问一次
 * - COMPLETED_WITH_NICKNAME：完成且已捕获称呼
 * - COMPLETED_WITHOUT_NICKNAME：完成但未捕获称呼（用户拒绝 / 二次仍模糊）
 */
@Entity(tableName = "first_meeting_state")
data class FirstMeetingStateEntity(
    @PrimaryKey
    val agentId: Long,
    val phase: String,
    /** 问候消息的 DB ID（问候入库后保存，用于幂等防重复） */
    val greetingMessageId: Long? = null,
    /** 问候发送时间戳 */
    val greetingSentAt: Long? = null,
    /** 用户回复计数（用于判断第几次未识别） */
    val userReplyCount: Int = 0,
    /** 是否已追问过一次 */
    val followUpAsked: Boolean = false,
    /** 是否已成功捕获称呼 */
    val nicknameCaptured: Boolean = false,
    /** 完成时间戳（COMPLETED_* 阶段非空） */
    val completedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
