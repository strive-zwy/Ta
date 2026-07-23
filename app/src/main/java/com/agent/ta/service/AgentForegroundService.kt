package com.agent.ta.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.agent.ta.data.model.AgentState
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Agent 前台服务
 *
 * 职责：
 * 1. 显示常驻通知，防止被杀
 * 2. 启动 AgentEngine（状态机 + 调度器）
 * 3. 状态切换时更新通知文案
 *
 * 通知中的 Agent 名字从 AgentConfigProvider 动态读取，
 * 导入自定义 Agent 后通知栏显示对应名字。
 */
class AgentForegroundService : Service() {

    private lateinit var notificationHelper: NotificationHelper
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 当前 Agent 名字（fallback 到"小雅"） */
    private val agentName: String
        get() = ServiceLocator.agentConfigProvider.get().agent.name.ifBlank { "小雅" }

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        val notification = notificationHelper.buildForegroundNotification("$agentName 正在启动...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_FOREGROUND, notification)
        }
        AgentEngine.start(this)
        observeStateChanges()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        AgentEngine.stop(this)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 观察状态变化，更新通知文案
     */
    private fun observeStateChanges() {
        scope.launch {
            AgentEngine.currentState.collect { state ->
                val text = when (state) {
                    AgentState.SLEEP -> "$agentName 正在睡觉..."
                    AgentState.WORK -> "$agentName 正在工作中..."
                    AgentState.GAME -> "$agentName 正在打游戏..."
                    AgentState.BATH -> "$agentName 正在洗澡..."
                    AgentState.BORED -> "$agentName 正在无聊中..."
                    AgentState.HAPPY -> "$agentName 心情不错..."
                }
                val notification = notificationHelper.buildForegroundNotification(text)
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NotificationHelper.NOTIFICATION_ID_FOREGROUND, notification)
            }
        }
    }
}
