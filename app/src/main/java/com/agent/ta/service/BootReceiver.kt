package com.agent.ta.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.agent.ta.TaApplication

/**
 * 开机自启接收器
 *
 * 接收 BOOT_COMPLETED 广播，启动 AgentForegroundService
 * 保证设备重启后 Agent 状态机继续运行
 *
 * 注意：从 Android 10 起，后台启动前台服务有限制，
 * 但 BOOT_COMPLETED 在用户解锁后允许启动前台服务
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "收到开机广播，启动 Agent 服务")

        // 确保 ServiceLocator 已初始化（通过 TaApplication.instance 触发 database 懒加载）
        val app = context.applicationContext as? TaApplication ?: return
        // 触发数据库懒加载
        app.database

        val serviceIntent = Intent(context, AgentForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "开机自启失败", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
