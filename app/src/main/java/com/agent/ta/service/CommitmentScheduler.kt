package com.agent.ta.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.agent.ta.data.local.entity.CommitmentEntity

/**
 * 承诺触发调度器
 *
 * 使用 AlarmManager 在承诺触发时间点精确唤醒，即使 App 未打开也能触发。
 * AlarmManager 触发 CommitmentTriggerReceiver，由 Receiver 调用 AgentEngine 发起主动消息。
 *
 * 作为主触发机制，Heartbeat 的 CommitmentObserver 作为兜底（App 打开时每 60 秒检测一次）。
 */
class CommitmentScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * 注册承诺触发闹钟
     *
     * @param commitment 承诺记录（必须有 triggerAt 且在未来）
     */
    fun scheduleCommitmentTrigger(commitment: CommitmentEntity) {
        val triggerAt = commitment.triggerAt ?: run {
            Log.w(TAG, "承诺无 triggerAt，跳过调度：${commitment.content}")
            return
        }
        if (triggerAt <= System.currentTimeMillis()) {
            Log.w(TAG, "承诺 triggerAt 已过期，跳过调度：${commitment.content}")
            return
        }

        val intent = Intent(context, CommitmentTriggerReceiver::class.java).apply {
            action = CommitmentTriggerReceiver.ACTION_COMMITMENT_TRIGGER
            putExtra(CommitmentTriggerReceiver.EXTRA_COMMITMENT_ID, commitment.id)
            putExtra(CommitmentTriggerReceiver.EXTRA_AGENT_ID, commitment.agentId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(commitment.agentId, commitment.id),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
            Log.d(TAG, "已注册承诺触发：${commitment.content}（triggerAt=$triggerAt）")
        } catch (e: SecurityException) {
            // Android 12+ 可能无 SCHEDULE_EXACT_ALARM 权限，降级为非精确闹钟
            Log.w(TAG, "无法注册精确闹钟，降级为非精确", e)
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    /**
     * 取消承诺触发闹钟
     *
     * 在承诺被完成/取消时调用，避免到点重复触发
     */
    fun cancelCommitmentTrigger(agentId: Long, commitmentId: Long) {
        val intent = Intent(context, CommitmentTriggerReceiver::class.java).apply {
            action = CommitmentTriggerReceiver.ACTION_COMMITMENT_TRIGGER
            putExtra(CommitmentTriggerReceiver.EXTRA_COMMITMENT_ID, commitmentId)
            putExtra(CommitmentTriggerReceiver.EXTRA_AGENT_ID, agentId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(agentId, commitmentId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "已取消承诺触发：commitmentId=$commitmentId")
        }
    }

    companion object {
        private const val TAG = "CommitmentScheduler"

        private fun requestCode(agentId: Long, commitmentId: Long): Int =
            31 * agentId.hashCode() + commitmentId.hashCode()
    }
}
