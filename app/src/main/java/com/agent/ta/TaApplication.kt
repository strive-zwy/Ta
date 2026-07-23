package com.agent.ta

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agent.ta.data.local.TaDatabase
import com.agent.ta.service.AgentForegroundService

class TaApplication : Application() {

    val database: TaDatabase by lazy {
        Room.databaseBuilder(
            this,
            TaDatabase::class.java,
            TaDatabase.DATABASE_NAME
        )
            // 4 → 5: ChatMessageEntity 新增 action / audioDurationSec 列，保留旧聊天记录
            .addMigrations(object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE chat_messages ADD COLUMN action TEXT")
                    db.execSQL("ALTER TABLE chat_messages ADD COLUMN audioDurationSec INTEGER")
                }
            })
            // 5 → 6: ChatMessageEntity 新增 emoji 列，支持纯表情消息
            .addMigrations(object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE chat_messages ADD COLUMN emoji TEXT")
                }
            })
            // 6 → 7: MemoryEntity 新增 accessCount 列，用于动态调整记忆重要性
            .addMigrations(object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE memories ADD COLUMN accessCount INTEGER NOT NULL DEFAULT 0")
                }
            })
            .fallbackToDestructiveMigration(true)
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
        lateinit var instance: TaApplication
            private set
    }
}
