package com.agent.ta.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.agent.ta.di.ServiceLocator
import com.agent.ta.domain.ChatInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 承诺触发广播接收器
 *
 * 由 AlarmManager 在承诺触发时间点唤醒。
 * 标记承诺为 triggered，构造 topicHint，调用 ChatInteractor.agentInitiate 发起主动消息。
 *
 * 与 Heartbeat 的 CommitmentObserver 互为兜底：
 * - AlarmManager 为主触发（App 关闭也能唤醒）
 * - CommitmentObserver 为兜底（App 打开时每 60 秒检测，补检 AlarmManager 遗漏的）
 */
class CommitmentTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMMITMENT_TRIGGER) return
        val commitmentId = intent.getLongExtra(EXTRA_COMMITMENT_ID, -1L)
        if (commitmentId == -1L) {
            Log.w(TAG, "收到广播但无 commitment_id，忽略")
            return
        }

        // 使用 goAsync 避免主线程阻塞（10 秒内必须 finish）
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val commitmentDao = ServiceLocator.commitmentDao
                val commitment = commitmentDao.getById(commitmentId)
                if (commitment == null) {
                    Log.w(TAG, "承诺不存在：id=$commitmentId")
                    return@launch
                }
                if (commitment.status != "pending") {
                    Log.d(TAG, "承诺已非 pending（${commitment.status}），跳过触发：${commitment.content}")
                    return@launch
                }

                // 1. 标记为 triggered
                commitmentDao.updateStatus(commitment.id, "triggered")

                // 2. 构造 topicHint 并触发主动消息
                val topicHint = when (commitment.type) {
                    "appointment" -> "到了和用户约定的时间：${commitment.content}。你可以说类似'时间到啦，你那边准备好了吗？'"
                    "promise" -> "你之前答应了用户：${commitment.content}。现在该去做了，可以告诉用户你开始做了"
                    "reminder" -> "你之前答应了提醒用户：${commitment.content}。现在该提醒用户了"
                    else -> "承诺时间到了：${commitment.content}"
                }

                // 3. 启动 ChatInteractor 发起主动消息
                val interactor = ChatInteractor(context)
                interactor.agentInitiate(topicHint)

                Log.d(TAG, "承诺触发成功：${commitment.content}")
            } catch (e: Exception) {
                Log.e(TAG, "承诺触发失败：id=$commitmentId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_COMMITMENT_TRIGGER = "com.agent.ta.ACTION_COMMITMENT_TRIGGER"
        const val EXTRA_COMMITMENT_ID = "extra_commitment_id"
        private const val TAG = "CommitmentTriggerReceiver"
    }
}
