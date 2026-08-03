package com.agent.ta

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
            // 7 → 8: 新增 daily_schedule / future_events / conversation_summaries 三张表
            // 老用户从 v7 升级到 v8 时不会触发 fallbackToDestructiveMigration，保留全部历史数据
            .addMigrations(object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // daily_schedule 表
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS daily_schedule (
                            date TEXT NOT NULL PRIMARY KEY,
                            slotsJson TEXT NOT NULL,
                            isAdjusted INTEGER NOT NULL DEFAULT 0,
                            source TEXT NOT NULL DEFAULT 'plan',
                            createdAt INTEGER NOT NULL DEFAULT 0,
                            updatedAt INTEGER NOT NULL DEFAULT 0
                        )""".trimIndent()
                    )
                    // future_events 表
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS future_events (
                            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                            date TEXT NOT NULL,
                            description TEXT NOT NULL,
                            source TEXT NOT NULL DEFAULT 'chat',
                            consumed INTEGER NOT NULL DEFAULT 0,
                            createdAt INTEGER NOT NULL DEFAULT 0
                        )""".trimIndent()
                    )
                    // conversation_summaries 表
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS conversation_summaries (
                            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                            bucketId INTEGER NOT NULL,
                            startMessageId INTEGER NOT NULL,
                            endMessageId INTEGER NOT NULL,
                            summary TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            messageCount INTEGER NOT NULL
                        )""".trimIndent()
                    )
                }
            })
            // 8 → 9: future_events 表新增 (date, description) 唯一索引，防止 LLM 重复提取同一事件
            .addMigrations(object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 删除重复记录（保留最早创建的，即 id 最小的）
                    db.execSQL(
                        "DELETE FROM future_events WHERE id NOT IN " +
                            "(SELECT MIN(id) FROM future_events GROUP BY date, description)"
                    )
                    // 添加唯一索引
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_future_events_date_description " +
                            "ON future_events(date, description)"
                    )
                }
            })
            // 9 → 10: daily_schedule 表新增 originalSlotsJson 列，保留原始计划作息快照
            // 老用户升级后 originalSlotsJson 为空字符串，下次重新生成时自动填充
            .addMigrations(object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE daily_schedule ADD COLUMN originalSlotsJson TEXT NOT NULL DEFAULT ''")
                }
            })
            // 10 → 11: 新增 daily_state 表，记录每天结构化状态参数（mood/fatigue/stress/energy 等）
            .addMigrations(object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS daily_state (
                            date TEXT NOT NULL PRIMARY KEY,
                            sleepTime TEXT,
                            wakeTime TEXT,
                            sleepDurationMin INTEGER,
                            mood REAL,
                            fatigue REAL,
                            stress REAL,
                            energy REAL,
                            mainActivities TEXT NOT NULL,
                            specialEvents TEXT NOT NULL,
                            hadInteractionWithUser INTEGER NOT NULL DEFAULT 0,
                            interactionCount INTEGER NOT NULL DEFAULT 0,
                            summary TEXT NOT NULL DEFAULT '',
                            createdAt INTEGER NOT NULL DEFAULT 0,
                            updatedAt INTEGER NOT NULL DEFAULT 0
                        )""".trimIndent()
                    )
                }
            })
            // 11 → 12: 新增 commitments 表，记录 Agent 与用户之间的承诺/约定/提醒
            .addMigrations(object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS commitments (
                            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                            type TEXT NOT NULL,
                            content TEXT NOT NULL,
                            participants TEXT NOT NULL,
                            triggerAt INTEGER,
                            deadline INTEGER,
                            status TEXT NOT NULL,
                            source TEXT NOT NULL,
                            relatedMessageId INTEGER,
                            createdAt INTEGER NOT NULL DEFAULT 0,
                            updatedAt INTEGER NOT NULL DEFAULT 0
                        )""".trimIndent()
                    )
                }
            })
            // 12 → 13: 新增 relationship_state 和 milestone_events 表（Phase 2 关系系统）
            .addMigrations(object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 关系状态表（单条记录，id 固定为 1）
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS relationship_state (
                            id INTEGER NOT NULL PRIMARY KEY,
                            currentStage TEXT NOT NULL,
                            intimacyScore INTEGER NOT NULL,
                            trustScore INTEGER NOT NULL,
                            interactionCount INTEGER NOT NULL,
                            lastInteractionAt INTEGER NOT NULL,
                            lastDecayAt INTEGER NOT NULL,
                            createdAt INTEGER NOT NULL DEFAULT 0,
                            updatedAt INTEGER NOT NULL DEFAULT 0
                        )""".trimIndent()
                    )
                    // 插入初始记录：陌生阶段，所有分数为 0
                    val now = System.currentTimeMillis()
                    db.execSQL(
                        """INSERT INTO relationship_state (id, currentStage, intimacyScore, trustScore, interactionCount, lastInteractionAt, lastDecayAt, createdAt, updatedAt)
                           VALUES (1, 'stranger', 0, 0, 0, $now, $now, $now, $now)""".trimIndent()
                    )
                    // 里程碑事件表
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS milestone_events (
                            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                            type TEXT NOT NULL,
                            title TEXT NOT NULL,
                            triggeredAt INTEGER NOT NULL,
                            triggerSource TEXT NOT NULL,
                            contextSnapshot TEXT NOT NULL DEFAULT ''
                        )""".trimIndent()
                    )
                }
            })
            // 13 → 14: 新增 emotional_state 表（Phase 3 情感势能驱动主动发起）
            .addMigrations(object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Agent 情绪状态表（单条记录，id 固定为 1）
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS emotional_state (
                            id INTEGER NOT NULL PRIMARY KEY,
                            valence REAL NOT NULL,
                            arousal REAL NOT NULL,
                            potentialEnergy INTEGER NOT NULL,
                            lastEmotion TEXT,
                            lastUserInteractionAt INTEGER NOT NULL,
                            lastDecayAt INTEGER NOT NULL,
                            createdAt INTEGER NOT NULL DEFAULT 0,
                            updatedAt INTEGER NOT NULL DEFAULT 0
                        )""".trimIndent()
                    )
                    // 插入初始记录：中性情绪（valence=0.0, arousal=0.3, potentialEnergy=0）
                    val now = System.currentTimeMillis()
                    db.execSQL(
                        """INSERT INTO emotional_state (id, valence, arousal, potentialEnergy, lastEmotion, lastUserInteractionAt, lastDecayAt, createdAt, updatedAt)
                           VALUES (1, 0.0, 0.3, 0, NULL, $now, $now, $now, $now)""".trimIndent()
                    )
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
        @Volatile
        var instance: TaApplication? = null
            private set
    }
}
