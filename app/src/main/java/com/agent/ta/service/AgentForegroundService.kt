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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Agent 前台服务
 *
 * 职责：
 * 1. 显示常驻通知，防止被杀
 * 2. 启动 AgentEngine（状态机 + 调度器）
 * 3. 状态切换时更新通知文案
 * 4. Agent 配置变化时（如导入新配置）刷新通知中的 Agent 名字
 *
 * 通知中的 Agent 名字从 AgentConfigProvider.config StateFlow 动态读取，
 * 导入自定义 Agent 后通知栏会自动更新为新 Agent 名字。
 */
class AgentForegroundService : Service() {

    private lateinit var notificationHelper: NotificationHelper
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        val initName = currentAgentName()
        val notification = notificationHelper.buildForegroundNotification("$initName 正在启动...")
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
        observeStateAndConfigChanges()
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

    /** 当前 Agent 名字（fallback 到"小雅"） */
    private fun currentAgentName(): String =
        ServiceLocator.agentConfigProvider.get().agent.name.ifBlank { "小雅" }

    /**
     * 同时观察状态变化和配置变化，更新通知文案
     *
     * combine：状态或配置任一变化都触发刷新。
     * 导入新 Agent 配置后，AgentConfigProvider.config 会发射新值，
     * 即使当前状态没变，通知栏也会用新 Agent 名字重新构建文案。
     */
    private fun observeStateAndConfigChanges() {
        scope.launch {
            AgentEngine.currentState
                .combine(ServiceLocator.agentConfigProvider.config) { state, config ->
                    Pair(state, config)
                }
                .collect { (state, config) ->
                    val name = config.agent.name.ifBlank { "小雅" }
                    val text = when (state) {
                        AgentState.NORMAL -> "$name 在线..."
                        AgentState.BUSY -> "$name 正在忙碌..."
                        AgentState.IDLE -> "$name 正在空闲..."
                        AgentState.UNAVAILABLE -> "$name 暂时无法回复..."
                    }
                    val notification = notificationHelper.buildForegroundNotification(text)
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NotificationHelper.NOTIFICATION_ID_FOREGROUND, notification)
                }
        }
    }
}
