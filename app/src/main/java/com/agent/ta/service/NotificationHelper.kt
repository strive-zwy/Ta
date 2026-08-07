package com.agent.ta.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.agent.ta.MainActivity
import com.agent.ta.R
import com.agent.ta.di.ServiceLocator

/**
 * 通知管理
 *
 * 通知标题（Agent 名字）动态从 AgentConfigProvider 读取，
 * 导入自定义 Agent 后通知栏显示对应名字，而不是硬编码的"小雅"。
 */
class NotificationHelper(private val context: Context) {

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                "Agent 状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Agent 运行状态"
                setShowBadge(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGE,
                "Agent 消息",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Agent 发来的消息"
                enableVibration(true)
            }
        )
    }

    /**
     * 当前 Agent 名字（从配置读取，未导入时回退为"小雅"）
     */
    private val agentName: String
        get() = ServiceLocator.agentConfigProvider.get().agent.name.ifBlank { "小雅" }

    /**
     * Agent 消息通知
     *
     * @param agentName 消息所属 Agent 的名称（由调用方从消息的 agentId 解析传入，
     *                  不使用当前 active Agent 覆盖，防止切换期间通知张冠李戴）
     */
    fun notifyAgentMessage(text: String, audioPath: String?, agentName: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(agentName)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID_MESSAGE, notification)
    }

    /**
     * 前台服务通知
     */
    fun buildForegroundNotification(text: String): android.app.Notification {
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(agentName)
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_STATUS = "agent_status"
        const val CHANNEL_MESSAGE = "agent_message"
        const val NOTIFICATION_ID_FOREGROUND = 1
        const val NOTIFICATION_ID_MESSAGE = 2
    }
}
