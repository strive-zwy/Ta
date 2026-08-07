package com.agent.ta.domain.firstmeeting

import android.util.Log
import com.agent.ta.data.local.dao.FirstMeetingStateDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 首次见面状态机协调器
 *
 * 职责：
 * - 管理 NOT_STARTED → GREETING_IN_PROGRESS → WAITING_NICKNAME → 完成 的流转
 * - 通过条件更新（updatePhaseIf）实现并发抢占，防止并发生成两次问候
 * - LLM 失败回退到 NOT_STARTED；TTS 失败不回退（文字已成功入库）
 * - 第一次未识别称呼追问一次（FOLLOW_UP_ASKED），第二次仍不明确则结束
 * - 用户明确拒绝立即结束，不继续追问
 *
 * 设计原则：所有方法接收 agentId，结果只写回该 Agent 的状态记录。
 */
class FirstMeetingCoordinator(private val dao: FirstMeetingStateDao) {

    /**
     * 开始问候：NOT_STARTED → GREETING_IN_PROGRESS
     *
     * 使用条件更新（CAS）抢占，防止并发生成两次问候。
     *
     * @return true 表示抢占成功，调用方应继续生成问候；false 表示状态已变更或已完成，应跳过
     */
    suspend fun beginGreeting(agentId: Long): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val affected = dao.updatePhaseIf(
            agentId = agentId,
            fromPhase = FirstMeetingPhase.NOT_STARTED.id,
            toPhase = FirstMeetingPhase.GREETING_IN_PROGRESS.id,
            updatedAt = now
        )
        if (affected == 1) {
            Log.d(TAG, "beginGreeting: agentId=$agentId 抢占成功 NOT_STARTED → GREETING_IN_PROGRESS")
            true
        } else {
            Log.d(TAG, "beginGreeting: agentId=$agentId 抢占失败（状态已变更或已完成）")
            false
        }
    }

    /**
     * 问候成功入库：GREETING_IN_PROGRESS → WAITING_NICKNAME
     *
     * 保存 greetingMessageId 和 greetingSentAt，进入等待用户称呼阶段。
     *
     * @throws IllegalStateException 如果当前状态不是 GREETING_IN_PROGRESS
     */
    suspend fun onGreetingSuccess(agentId: Long, messageId: Long, sentAt: Long) =
        withContext(Dispatchers.IO) {
            val current = dao.getByAgentId(agentId)
            val currentPhase = current?.phase?.let { FirstMeetingPhase.fromId(it) }
            if (currentPhase != FirstMeetingPhase.GREETING_IN_PROGRESS) {
                throw IllegalStateException(
                    "onGreetingSuccess 要求 GREETING_IN_PROGRESS，当前为 $currentPhase（agentId=$agentId）"
                )
            }
            dao.updateGreeting(
                agentId = agentId,
                messageId = messageId,
                sentAt = sentAt,
                phase = FirstMeetingPhase.WAITING_NICKNAME.id,
                updatedAt = System.currentTimeMillis()
            )
            Log.d(TAG, "onGreetingSuccess: agentId=$agentId → WAITING_NICKNAME（messageId=$messageId）")
        }

    /**
     * LLM 生成问候失败：GREETING_IN_PROGRESS → NOT_STARTED
     *
     * 回退状态，允许下次进入聊天页时重试。
     */
    suspend fun onGreetingLlmFailure(agentId: Long) = withContext(Dispatchers.IO) {
        dao.updatePhaseIf(
            agentId = agentId,
            fromPhase = FirstMeetingPhase.GREETING_IN_PROGRESS.id,
            toPhase = FirstMeetingPhase.NOT_STARTED.id,
            updatedAt = System.currentTimeMillis()
        )
        Log.d(TAG, "onGreetingLlmFailure: agentId=$agentId 回退 → NOT_STARTED")
    }

    /**
     * TTS 失败：不回退，文字已成功入库
     *
     * 保存 greetingMessageId 并进入 WAITING_NICKNAME（与 onGreetingSuccess 相同逻辑）。
     * TTS 失败不影响首次见面流程，用户仍可看到文字问候并回复称呼。
     */
    suspend fun onGreetingTtsFailure(agentId: Long, messageId: Long, sentAt: Long) =
        withContext(Dispatchers.IO) {
            dao.updateGreeting(
                agentId = agentId,
                messageId = messageId,
                sentAt = sentAt,
                phase = FirstMeetingPhase.WAITING_NICKNAME.id,
                updatedAt = System.currentTimeMillis()
            )
            Log.d(TAG, "onGreetingTtsFailure: agentId=$agentId → WAITING_NICKNAME（TTS 失败但文字已入库）")
        }

    /**
     * 用户给出明确称呼：WAITING_NICKNAME / FOLLOW_UP_ASKED → COMPLETED_WITH_NICKNAME
     *
     * @throws IllegalStateException 如果当前状态不在等待称呼阶段
     */
    suspend fun onNicknameCaptured(agentId: Long, nickname: String) = withContext(Dispatchers.IO) {
        val current = dao.getByAgentId(agentId)
        val currentPhase = current?.phase?.let { FirstMeetingPhase.fromId(it) }
        if (currentPhase == null || !currentPhase.isAwaitingNickname) {
            throw IllegalStateException(
                "onNicknameCaptured 要求 WAITING_NICKNAME 或 FOLLOW_UP_ASKED，当前为 $currentPhase（agentId=$agentId）"
            )
        }
        dao.updatePhase(
            agentId = agentId,
            phase = FirstMeetingPhase.COMPLETED_WITH_NICKNAME.id,
            updatedAt = System.currentTimeMillis()
        )
        Log.d(TAG, "onNicknameCaptured: agentId=$agentId → COMPLETED_WITH_NICKNAME（nickname=$nickname）")
    }

    /**
     * 用户回复未识别到明确称呼：
     * - WAITING_NICKNAME → FOLLOW_UP_ASKED（第一次，追问一次）
     * - FOLLOW_UP_ASKED → COMPLETED_WITHOUT_NICKNAME（第二次仍不明确，结束不打扰）
     *
     * @throws IllegalStateException 如果当前状态不在等待称呼阶段
     */
    suspend fun onNicknameUnrecognized(agentId: Long) = withContext(Dispatchers.IO) {
        val current = dao.getByAgentId(agentId)
        val currentPhase = current?.phase?.let { FirstMeetingPhase.fromId(it) }
        when (currentPhase) {
            FirstMeetingPhase.WAITING_NICKNAME -> {
                dao.updatePhase(
                    agentId = agentId,
                    phase = FirstMeetingPhase.FOLLOW_UP_ASKED.id,
                    updatedAt = System.currentTimeMillis()
                )
                Log.d(TAG, "onNicknameUnrecognized: agentId=$agentId → FOLLOW_UP_ASKED（第一次追问）")
            }
            FirstMeetingPhase.FOLLOW_UP_ASKED -> {
                dao.updatePhase(
                    agentId = agentId,
                    phase = FirstMeetingPhase.COMPLETED_WITHOUT_NICKNAME.id,
                    updatedAt = System.currentTimeMillis()
                )
                Log.d(TAG, "onNicknameUnrecognized: agentId=$agentId → COMPLETED_WITHOUT_NICKNAME（第二次仍未明确）")
            }
            else -> throw IllegalStateException(
                "onNicknameUnrecognized 要求 WAITING_NICKNAME 或 FOLLOW_UP_ASKED，当前为 $currentPhase（agentId=$agentId）"
            )
        }
    }

    /**
     * 用户明确拒绝提供称呼：WAITING_NICKNAME / FOLLOW_UP_ASKED → COMPLETED_WITHOUT_NICKNAME
     *
     * 立即结束，不继续追问。
     *
     * @throws IllegalStateException 如果当前状态不在等待称呼阶段
     */
    suspend fun onUserDeclined(agentId: Long) = withContext(Dispatchers.IO) {
        val current = dao.getByAgentId(agentId)
        val currentPhase = current?.phase?.let { FirstMeetingPhase.fromId(it) }
        if (currentPhase == null || !currentPhase.isAwaitingNickname) {
            throw IllegalStateException(
                "onUserDeclined 要求 WAITING_NICKNAME 或 FOLLOW_UP_ASKED，当前为 $currentPhase（agentId=$agentId）"
            )
        }
        dao.updatePhase(
            agentId = agentId,
            phase = FirstMeetingPhase.COMPLETED_WITHOUT_NICKNAME.id,
            updatedAt = System.currentTimeMillis()
        )
        Log.d(TAG, "onUserDeclined: agentId=$agentId → COMPLETED_WITHOUT_NICKNAME（用户明确拒绝）")
    }

    /**
     * 获取当前首次见面阶段（Task 15）
     *
     * ChatInteractor 在生成回复前调用，用于判断：
     * - 是否为首次见面场景（NOT_STARTED / GREETING_IN_PROGRESS）
     * - 是否需要注入 nicknameResolution 引导（WAITING_NICKNAME / FOLLOW_UP_ASKED）
     *
     * @return 当前阶段；无记录或异常时返回 null（调用方视为已完成，走普通对话路径）
     */
    suspend fun getPhase(agentId: Long): FirstMeetingPhase? = withContext(Dispatchers.IO) {
        dao.getByAgentId(agentId)?.phase?.let { FirstMeetingPhase.fromId(it) }
    }

    /**
     * 是否处于等待用户给出称呼的阶段（Task 15）
     *
     * WAITING_NICKNAME / FOLLOW_UP_ASKED 时为 true，PromptBuilder 会注入 nicknameResolution 引导。
     */
    suspend fun isAwaitingNickname(agentId: Long): Boolean = withContext(Dispatchers.IO) {
        getPhase(agentId)?.isAwaitingNickname == true
    }

    /**
     * 标记问候消息已入库并推进到等待称呼阶段（Task 15）
     *
     * 与 onGreetingSuccess 相同的 DB 操作，但不做 GREETING_IN_PROGRESS 校验，
     * 用于 FIRST_MEETING_REPLY 场景：用户先发消息时 beginGreeting 成功后直接用 FIRST_MEETING_REPLY 生成回复，
     * 回复入库后调用此方法推进状态。此时状态可能是 GREETING_IN_PROGRESS。
     *
     * 如果当前状态不是 GREETING_IN_PROGRESS（如已被其他流程推进），则静默跳过。
     */
    suspend fun markGreetingCompletedIfInProgress(agentId: Long, messageId: Long, sentAt: Long) =
        withContext(Dispatchers.IO) {
            val current = dao.getByAgentId(agentId)
            val currentPhase = current?.phase?.let { FirstMeetingPhase.fromId(it) }
            if (currentPhase != FirstMeetingPhase.GREETING_IN_PROGRESS) {
                Log.d(TAG, "markGreetingCompletedIfInProgress: agentId=$agentId 当前为 $currentPhase，非 GREETING_IN_PROGRESS，跳过")
                return@withContext
            }
            dao.updateGreeting(
                agentId = agentId,
                messageId = messageId,
                sentAt = sentAt,
                phase = FirstMeetingPhase.WAITING_NICKNAME.id,
                updatedAt = System.currentTimeMillis()
            )
            Log.d(TAG, "markGreetingCompletedIfInProgress: agentId=$agentId → WAITING_NICKNAME（messageId=$messageId）")
        }

    companion object {
        private const val TAG = "FirstMeetingCoordinator"
    }
}
