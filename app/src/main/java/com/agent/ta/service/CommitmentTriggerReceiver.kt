package com.agent.ta.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.agent.ta.di.ServiceLocator
import com.agent.ta.domain.ChatInteractor
import com.agent.ta.domain.CommitmentRetryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 承诺触发广播接收器
 *
 * 由 AlarmManager 在承诺触发时间点唤醒。
 * 原子领取承诺，等待主动消息落库后确认交付。
 *
 * 与 Heartbeat 的 CommitmentObserver 互为兜底：
 * - AlarmManager 为主触发（App 关闭也能唤醒）
 * - CommitmentObserver 为兜底（App 打开时每 60 秒检测，补检 AlarmManager 遗漏的）
 *
 * 多 Agent 隔离：使用 PendingIntent extras 中的 agentId 处理状态和写消息，
 * 不查询 active agent 替代。非当前 Agent 的定时任务仍写入对应会话。
 */
class CommitmentTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMMITMENT_TRIGGER) return
        val commitmentId = intent.getLongExtra(EXTRA_COMMITMENT_ID, -1L)
        val agentId = intent.getLongExtra(EXTRA_AGENT_ID, -1L)
        if (commitmentId == -1L || agentId == -1L) {
            Log.w(TAG, "收到广播但无 commitment_id 或 agent_id，忽略")
            return
        }

        // 使用 goAsync 避免主线程阻塞（10 秒内必须 finish）
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val commitmentDao = ServiceLocator.commitmentDao
                val commitment = commitmentDao.getById(agentId, commitmentId)
                if (commitment == null) {
                    Log.w(TAG, "承诺不存在：agentId=$agentId, id=$commitmentId")
                    return@launch
                }
                val timerEnabled = ServiceLocator.userPreferences.commitmentTimerEnabled
                if (!timerEnabled) {
                    Log.d(TAG, "定时提醒已关闭，跳过承诺触发：id=${commitment.id}")
                    return@launch
                }
                if (commitment.status != "pending" ||
                    commitmentDao.claimPending(agentId, commitment.id, System.currentTimeMillis()) != 1
                ) {
                    Log.d(TAG, "承诺无法领取（${commitment.status}），跳过触发：id=${commitment.id}")
                    return@launch
                }

                // 读取承诺定时提醒开关：关闭时只标记状态，不主动发消息（靠 LLM 在对话中自然提醒）
                // 2. 构造 topicHint 并触发主动消息
                val topicHint = when (commitment.type) {
                    "appointment" -> "到了和用户约定的时间：${commitment.content}。你可以说类似'时间到啦，你那边准备好了吗？'"
                    "promise" -> "你之前答应了用户：${commitment.content}。现在该去做了，可以告诉用户你开始做了"
                    "reminder" -> "你之前答应了提醒用户：${commitment.content}。现在该提醒用户了"
                    else -> "承诺时间到了：${commitment.content}"
                }

                // 3. 启动 ChatInteractor 发起主动消息
                val interactor = ChatInteractor(context)
                val delivered = interactor.agentInitiateAndWait(agentId, topicHint)
                if (delivered) {
                    commitmentDao.markDelivered(agentId, commitment.id, System.currentTimeMillis())
                    Log.d(TAG, "承诺触发成功：id=${commitment.id}")
                } else {
                    releaseAfterFailure(context, commitmentDao, commitment, agentId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "承诺触发失败：agentId=$agentId, id=$commitmentId", e)
                val commitmentDao = ServiceLocator.commitmentDao
                val commitment = commitmentDao.getById(agentId, commitmentId)
                if (commitment != null) {
                    releaseAfterFailure(context, commitmentDao, commitment, agentId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun releaseAfterFailure(
        context: Context,
        dao: com.agent.ta.data.local.dao.CommitmentDao,
        commitment: com.agent.ta.data.local.entity.CommitmentEntity,
        agentId: Long
    ) {
        val nextRetryCount = commitment.retryCount + 1
        val status = CommitmentRetryPolicy.statusAfterFailure(commitment.retryCount)
        val nextRetryAt = if (status == "pending") {
            System.currentTimeMillis() + CommitmentRetryPolicy.delayMs(nextRetryCount)
        } else null
        val released = dao.releaseAfterFailure(
            agentId,
            commitment.id,
            status,
            nextRetryAt,
            System.currentTimeMillis()
        )
        if (released == 1 && nextRetryAt != null) {
            CommitmentScheduler(context).scheduleCommitmentTrigger(
                commitment.copy(
                    status = "pending",
                    triggerAt = nextRetryAt,
                    retryCount = nextRetryCount,
                    nextRetryAt = nextRetryAt
                )
            )
        }
    }

    companion object {
        const val ACTION_COMMITMENT_TRIGGER = "com.agent.ta.ACTION_COMMITMENT_TRIGGER"
        const val EXTRA_COMMITMENT_ID = "extra_commitment_id"
        const val EXTRA_AGENT_ID = "extra_agent_id"
        private const val TAG = "CommitmentTriggerReceiver"
    }
}
