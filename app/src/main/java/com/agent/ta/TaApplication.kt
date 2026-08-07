package com.agent.ta

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.room.Room
import com.agent.ta.data.local.DatabaseMigrations
import com.agent.ta.data.local.TaDatabase
import com.agent.ta.service.AgentForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TaApplication : Application() {

    /**
     * 应用级协程作用域
     *
     * 用于不依赖 Compose composition 生命周期的耗时操作（如 SAF launcher 回调中的导入/导出）。
     * rememberCoroutineScope 在 composable 离开 composition 后会被取消，
     * 而 SAF launcher 启动系统文件选择器可能触发 composable 重建，导致回调时 scope 已失效。
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: TaDatabase by lazy {
        Room.databaseBuilder(
            this,
            TaDatabase::class.java,
            TaDatabase.DATABASE_NAME
        )
            .addMigrations(*DatabaseMigrations.ALL)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 启动前台服务
        val serviceIntent = Intent(this, AgentForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    companion object {
        @Volatile
        var instance: TaApplication? = null
            private set
    }
}
