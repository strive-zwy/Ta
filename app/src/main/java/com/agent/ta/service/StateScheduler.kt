package com.agent.ta.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.agent.ta.data.model.AgentState

/**
 * 状态切换广播接收器
 * AlarmManager 到点触发，切换到下一个状态
 */
class StateSwitchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val newStateId = intent.getStringExtra(EXTRA_STATE) ?: return
        val state = AgentState.fromId(newStateId) ?: return
        Log.d(TAG, "收到状态切换广播：${state.displayName}")
        AgentEngine.onStateSwitched(context, state)
    }

    companion object {
        const val ACTION_STATE_SWITCH = "com.agent.ta.ACTION_STATE_SWITCH"
        const val EXTRA_STATE = "extra_state"
        private const val TAG = "StateSwitchReceiver"
    }
}

/**
 * 状态机调度器
 *
 * 改造说明：
 * - 不再从 ScheduleConfig 读取固定 slots
 * - 改为从 StateMachine.getUpcomingSwitches() 获取当天切换点
 * - 当作息被 Agent 调整后，重新注册
 *
 * 一致性修复：用 requestCode 集合追踪已注册的闹钟，cancelAll 时精确取消
 */
class StateScheduler(private val context: Context) {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** 已注册的 requestCode 集合，cancelAll 时按这些值精确取消 */
    private val registeredRequestCodes = mutableSetOf<Int>()

    /**
     * 注册接下来的状态切换任务（基于当天作息）
     *
     * @param switches 切换点列表（epochMilli, 目标状态）
     */
    fun scheduleNextSwitches(switches: List<Pair<Long, AgentState>>) {
        cancelAll()

        switches.forEach { (triggerAt, state) ->
            scheduleSwitch(triggerAt, state)
        }

        Log.d(TAG, "已注册 ${switches.size} 个状态切换任务")
    }

    /**
     * 注册单个状态切换
     */
    private fun scheduleSwitch(triggerAt: Long, state: AgentState) {
        val intent = Intent(context, StateSwitchReceiver::class.java).apply {
            action = StateSwitchReceiver.ACTION_STATE_SWITCH
            putExtra(StateSwitchReceiver.EXTRA_STATE, state.id)
        }
        // 用 triggerAt 作为 requestCode 避免冲突
        val requestCode = (triggerAt % Int.MAX_VALUE).toInt()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
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
            registeredRequestCodes.add(requestCode)
        } catch (e: SecurityException) {
            Log.w(TAG, "无法注册精确闹钟，降级为非精确", e)
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            registeredRequestCodes.add(requestCode)
        }
    }

    /**
     * 取消所有已注册的状态切换
     * 使用 registeredRequestCodes 精确匹配注册时的 requestCode
     */
    fun cancelAll() {
        // 按 triggerAt-time-hash 注册的 requestCode 精确取消
        val stateIds = AgentState.entries.map { it.id }
        val iterator = registeredRequestCodes.iterator()
        while (iterator.hasNext()) {
            val requestCode = iterator.next()
            // 取消时需要构造一个匹配的 intent（action + state extra），
            // 但 FLAG_NO_CREATE 按 requestCode 匹配，不校验 extra，故任选一个 state 构造即可
            val intent = Intent(context, StateSwitchReceiver::class.java).apply {
                action = StateSwitchReceiver.ACTION_STATE_SWITCH
                putExtra(StateSwitchReceiver.EXTRA_STATE, stateIds.first())
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
            }
            iterator.remove()
        }
    }

    companion object {
        private const val TAG = "StateScheduler"
    }
}
