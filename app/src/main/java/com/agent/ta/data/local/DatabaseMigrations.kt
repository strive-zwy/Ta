package com.agent.ta.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agent.ta.data.default.DefaultAgent
import com.agent.ta.data.model.AgentConfig
import kotlinx.serialization.json.Json

/**
 * Room 数据库迁移集中管理
 *
 * 所有版本迁移从此处注册，避免散落在 Application 中。
 * v14→v15 迁移实现多 Agent 数据隔离（为全部业务表新增 agentId）。
 */
object DatabaseMigrations {

    // ───────────────────────────────────────────────────────────────────────────
    // 历史迁移（4 → 14），从 TaApplication 搬迁至此
    // ───────────────────────────────────────────────────────────────────────────

    // 4 → 5: ChatMessageEntity 新增 action / audioDurationSec 列
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN action TEXT")
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN audioDurationSec INTEGER")
        }
    }

    // 5 → 6: ChatMessageEntity 新增 emoji 列
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN emoji TEXT")
        }
    }

    // 6 → 7: MemoryEntity 新增 accessCount 列
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE memories ADD COLUMN accessCount INTEGER NOT NULL DEFAULT 0")
        }
    }

    // 7 → 8: 新增 daily_schedule / future_events / conversation_summaries 三张表
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
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
    }

    // 8 → 9: future_events 唯一索引 (date, description)
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "DELETE FROM future_events WHERE id NOT IN " +
                    "(SELECT MIN(id) FROM future_events GROUP BY date, description)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_future_events_date_description " +
                    "ON future_events(date, description)"
            )
        }
    }

    // 9 → 10: daily_schedule 新增 originalSlotsJson 列
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE daily_schedule ADD COLUMN originalSlotsJson TEXT NOT NULL DEFAULT ''")
        }
    }

    // 10 → 11: 新增 daily_state 表
    val MIGRATION_10_11 = object : Migration(10, 11) {
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
    }

    // 11 → 12: 新增 commitments 表
    val MIGRATION_11_12 = object : Migration(11, 12) {
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
    }

    // 12 → 13: 新增 relationship_state 和 milestone_events 表
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
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
            val now = System.currentTimeMillis()
            db.execSQL(
                """INSERT INTO relationship_state (id, currentStage, intimacyScore, trustScore, interactionCount, lastInteractionAt, lastDecayAt, createdAt, updatedAt)
                   VALUES (1, 'stranger', 0, 0, 0, $now, $now, $now, $now)""".trimIndent()
            )
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
    }

    // 13 → 14: 新增 emotional_state 表
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
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
            val now = System.currentTimeMillis()
            db.execSQL(
                """INSERT INTO emotional_state (id, valence, arousal, potentialEnergy, lastEmotion, lastUserInteractionAt, lastDecayAt, createdAt, updatedAt)
                   VALUES (1, 0.0, 0.3, 0, NULL, $now, $now, $now, $now)""".trimIndent()
            )
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // 14 → 15: 多 Agent 数据隔离
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * v14 → v15：为全部业务表新增 agentId，实现多 Agent 数据隔离。
     *
     * 迁移规则：
     * 1. 确定旧数据归属 Agent：active 中最近导入且 ID 最大者；无 active 时选最近配置；无配置时插入默认配置。
     * 2. 重建全部 11 张业务表，保留原自增 ID、时间戳和逻辑引用。
     * 3. 旧数据统一归入迁移确定的 Agent。
     * 4. 当前 Agent 已有聊天记录时创建 COMPLETED_WITHOUT_NICKNAME；无聊天时创建 NOT_STARTED。
     * 5. 其他历史 Agent 创建 NOT_STARTED 状态和独立默认关系/情绪记录。
     * 6. 删除旧唯一索引，建立 Agent 维度的新索引。
     */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. 确定旧数据归属 Agent
            val agentId = determineOwningAgentId(db)
            val now = System.currentTimeMillis()

            // 2. 重建 chat_messages（新增 agentId）
            rebuildTable(
                db, "chat_messages",
                """CREATE TABLE IF NOT EXISTS chat_messages_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    agentId INTEGER NOT NULL,
                    direction TEXT NOT NULL, text TEXT, audioPath TEXT, directorPrompt TEXT,
                    state TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL,
                    repliedAt INTEGER, action TEXT, audioDurationSec INTEGER, emoji TEXT
                )""".trimIndent(),
                """INSERT INTO chat_messages_new (id, agentId, direction, text, audioPath, directorPrompt,
                    state, status, createdAt, repliedAt, action, audioDurationSec, emoji)
                    SELECT id, $agentId, direction, text, audioPath, directorPrompt,
                    state, status, createdAt, repliedAt, action, audioDurationSec, emoji
                    FROM chat_messages""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_agentId_createdAt ON chat_messages(agentId, createdAt)")

            // 3. 重建 state_log（新增 agentId）
            rebuildTable(
                db, "state_log",
                """CREATE TABLE IF NOT EXISTS state_log_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    agentId INTEGER NOT NULL,
                    state TEXT NOT NULL, enteredAt INTEGER NOT NULL, exitedAt INTEGER
                )""".trimIndent(),
                """INSERT INTO state_log_new (id, agentId, state, enteredAt, exitedAt)
                    SELECT id, $agentId, state, enteredAt, exitedAt FROM state_log""".trimIndent()
            )

            // 4. 重建 memories（新增 agentId）
            rebuildTable(
                db, "memories",
                """CREATE TABLE IF NOT EXISTS memories_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    agentId INTEGER NOT NULL,
                    type TEXT NOT NULL, category TEXT NOT NULL, content TEXT NOT NULL,
                    importance INTEGER NOT NULL, source TEXT NOT NULL,
                    createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, accessCount INTEGER NOT NULL
                )""".trimIndent(),
                """INSERT INTO memories_new (id, agentId, type, category, content, importance, source, createdAt, updatedAt, accessCount)
                    SELECT id, $agentId, type, category, content, importance, source, createdAt, updatedAt, accessCount
                    FROM memories""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_agentId_importance ON memories(agentId, importance)")

            // 5. 重建 daily_schedule（复合主键 agentId+date）
            rebuildTable(
                db, "daily_schedule",
                """CREATE TABLE IF NOT EXISTS daily_schedule_new (
                    agentId INTEGER NOT NULL, date TEXT NOT NULL,
                    slotsJson TEXT NOT NULL, originalSlotsJson TEXT NOT NULL,
                    isAdjusted INTEGER NOT NULL, source TEXT NOT NULL,
                    createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(agentId, date)
                )""".trimIndent(),
                """INSERT INTO daily_schedule_new (agentId, date, slotsJson, originalSlotsJson, isAdjusted, source, createdAt, updatedAt)
                    SELECT $agentId, date, slotsJson, originalSlotsJson, isAdjusted, source, createdAt, updatedAt
                    FROM daily_schedule""".trimIndent()
            )

            // 6. 重建 future_events（唯一索引改为 agentId+date+description）
            // 先删除旧唯一索引
            db.execSQL("DROP INDEX IF EXISTS index_future_events_date_description")
            rebuildTable(
                db, "future_events",
                """CREATE TABLE IF NOT EXISTS future_events_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    agentId INTEGER NOT NULL,
                    date TEXT NOT NULL, description TEXT NOT NULL,
                    source TEXT NOT NULL, consumed INTEGER NOT NULL, createdAt INTEGER NOT NULL
                )""".trimIndent(),
                """INSERT INTO future_events_new (id, agentId, date, description, source, consumed, createdAt)
                    SELECT id, $agentId, date, description, source, consumed, createdAt
                    FROM future_events""".trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_future_events_agentId_date_description ON future_events(agentId, date, description)")

            // 7. 重建 conversation_summaries（新增 agentId）
            rebuildTable(
                db, "conversation_summaries",
                """CREATE TABLE IF NOT EXISTS conversation_summaries_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    agentId INTEGER NOT NULL,
                    bucketId INTEGER NOT NULL, startMessageId INTEGER NOT NULL,
                    endMessageId INTEGER NOT NULL, summary TEXT NOT NULL,
                    createdAt INTEGER NOT NULL, messageCount INTEGER NOT NULL
                )""".trimIndent(),
                """INSERT INTO conversation_summaries_new (id, agentId, bucketId, startMessageId, endMessageId, summary, createdAt, messageCount)
                    SELECT id, $agentId, bucketId, startMessageId, endMessageId, summary, createdAt, messageCount
                    FROM conversation_summaries""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_summaries_agentId_bucketId ON conversation_summaries(agentId, bucketId)")

            // 8. 重建 daily_state（复合主键 agentId+date）
            rebuildTable(
                db, "daily_state",
                """CREATE TABLE IF NOT EXISTS daily_state_new (
                    agentId INTEGER NOT NULL, date TEXT NOT NULL,
                    sleepTime TEXT, wakeTime TEXT, sleepDurationMin INTEGER,
                    mood REAL, fatigue REAL, stress REAL, energy REAL,
                    mainActivities TEXT NOT NULL, specialEvents TEXT NOT NULL,
                    hadInteractionWithUser INTEGER NOT NULL, interactionCount INTEGER NOT NULL,
                    summary TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(agentId, date)
                )""".trimIndent(),
                """INSERT INTO daily_state_new (agentId, date, sleepTime, wakeTime, sleepDurationMin, mood, fatigue, stress, energy,
                    mainActivities, specialEvents, hadInteractionWithUser, interactionCount, summary, createdAt, updatedAt)
                    SELECT $agentId, date, sleepTime, wakeTime, sleepDurationMin, mood, fatigue, stress, energy,
                    mainActivities, specialEvents, hadInteractionWithUser, interactionCount, summary, createdAt, updatedAt
                    FROM daily_state""".trimIndent()
            )

            // 9. 重建 commitments（新增 agentId）
            rebuildTable(
                db, "commitments",
                """CREATE TABLE IF NOT EXISTS commitments_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    agentId INTEGER NOT NULL,
                    type TEXT NOT NULL, content TEXT NOT NULL, participants TEXT NOT NULL,
                    triggerAt INTEGER, deadline INTEGER, status TEXT NOT NULL,
                    source TEXT NOT NULL, relatedMessageId INTEGER,
                    createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL
                )""".trimIndent(),
                """INSERT INTO commitments_new (id, agentId, type, content, participants, triggerAt, deadline, status, source, relatedMessageId, createdAt, updatedAt)
                    SELECT id, $agentId, type, content, participants, triggerAt, deadline, status, source, relatedMessageId, createdAt, updatedAt
                    FROM commitments""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_agentId_status_triggerAt ON commitments(agentId, status, triggerAt)")

            // 10. 重建 milestone_events（新增 agentId）
            rebuildTable(
                db, "milestone_events",
                """CREATE TABLE IF NOT EXISTS milestone_events_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    agentId INTEGER NOT NULL,
                    type TEXT NOT NULL, title TEXT NOT NULL, triggeredAt INTEGER NOT NULL,
                    triggerSource TEXT NOT NULL, contextSnapshot TEXT NOT NULL
                )""".trimIndent(),
                """INSERT INTO milestone_events_new (id, agentId, type, title, triggeredAt, triggerSource, contextSnapshot)
                    SELECT id, $agentId, type, title, triggeredAt, triggerSource, contextSnapshot
                    FROM milestone_events""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_milestone_events_agentId_type_triggeredAt ON milestone_events(agentId, type, triggeredAt)")

            // 11. 重建 relationship_state（主键改为 agentId，每个 Agent 一条）
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS relationship_state_new (
                    agentId INTEGER NOT NULL,
                    currentStage TEXT NOT NULL, intimacyScore INTEGER NOT NULL,
                    trustScore INTEGER NOT NULL, interactionCount INTEGER NOT NULL,
                    lastInteractionAt INTEGER NOT NULL, lastDecayAt INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(agentId)
                )""".trimIndent()
            )
            // 迁移旧记录（id=1）到确定的 Agent
            db.execSQL(
                """INSERT INTO relationship_state_new (agentId, currentStage, intimacyScore, trustScore, interactionCount, lastInteractionAt, lastDecayAt, createdAt, updatedAt)
                    SELECT $agentId, currentStage, intimacyScore, trustScore, interactionCount, lastInteractionAt, lastDecayAt, createdAt, updatedAt
                    FROM relationship_state WHERE id = 1""".trimIndent()
            )
            db.execSQL("DROP TABLE relationship_state")
            db.execSQL("ALTER TABLE relationship_state_new RENAME TO relationship_state")

            // 12. 重建 emotional_state（主键改为 agentId，每个 Agent 一条）
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS emotional_state_new (
                    agentId INTEGER NOT NULL,
                    valence REAL NOT NULL, arousal REAL NOT NULL, potentialEnergy INTEGER NOT NULL,
                    lastEmotion TEXT, lastUserInteractionAt INTEGER NOT NULL, lastDecayAt INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(agentId)
                )""".trimIndent()
            )
            db.execSQL(
                """INSERT INTO emotional_state_new (agentId, valence, arousal, potentialEnergy, lastEmotion, lastUserInteractionAt, lastDecayAt, createdAt, updatedAt)
                    SELECT $agentId, valence, arousal, potentialEnergy, lastEmotion, lastUserInteractionAt, lastDecayAt, createdAt, updatedAt
                    FROM emotional_state WHERE id = 1""".trimIndent()
            )
            db.execSQL("DROP TABLE emotional_state")
            db.execSQL("ALTER TABLE emotional_state_new RENAME TO emotional_state")

            // 13. 为其他历史 Agent 创建独立默认关系/情绪记录
            createDefaultStateForOtherAgents(db, agentId, now)

            // 14. 创建 first_meeting_state 表并为每个 Agent 建立初始状态
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS first_meeting_state (
                    agentId INTEGER NOT NULL,
                    phase TEXT NOT NULL,
                    greetingMessageId INTEGER,
                    greetingSentAt INTEGER,
                    userReplyCount INTEGER NOT NULL,
                    followUpAsked INTEGER NOT NULL,
                    nicknameCaptured INTEGER NOT NULL,
                    completedAt INTEGER,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(agentId)
                )""".trimIndent()
            )
            createFirstMeetingStates(db, agentId, now)
        }

        /**
         * 确定旧数据归属的 Agent ID：
         * 1. active 中最近导入且 ID 最大者
         * 2. 无 active 时选最近配置（importedAt DESC, id DESC）
         * 3. 无配置时插入默认 Agent 配置
         */
        private fun determineOwningAgentId(db: SupportSQLiteDatabase): Long {
            // 优先选 active 中最近导入的
            db.query("SELECT id FROM agent_config WHERE isActive = 1 ORDER BY importedAt DESC, id DESC LIMIT 1").use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
            // 无 active，选最近配置
            db.query("SELECT id FROM agent_config ORDER BY importedAt DESC, id DESC LIMIT 1").use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
            // 无任何配置，插入默认 Agent
            val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
            val config = DefaultAgent.create()
            val configJson = json.encodeToString(AgentConfig.serializer(), config)
            val now = System.currentTimeMillis()
            db.execSQL(
                "INSERT INTO agent_config (configJson, agentName, importedAt, isActive) VALUES (?, ?, ?, 1)",
                arrayOf(configJson, config.agent.name.ifBlank { "未命名" }, now)
            )
            db.query("SELECT id FROM agent_config ORDER BY id DESC LIMIT 1").use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
            // 理论上不会走到这里
            return 1L
        }

        /**
         * 为确定的 Agent 之外的其他历史 Agent 创建独立默认关系和情绪记录。
         */
        private fun createDefaultStateForOtherAgents(db: SupportSQLiteDatabase, ownerAgentId: Long, now: Long) {
            db.query("SELECT id FROM agent_config").use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (id == ownerAgentId) continue
                    // 默认关系状态
                    db.execSQL(
                        """INSERT OR IGNORE INTO relationship_state
                            (agentId, currentStage, intimacyScore, trustScore, interactionCount, lastInteractionAt, lastDecayAt, createdAt, updatedAt)
                            VALUES ($id, 'stranger', 0, 0, 0, $now, $now, $now, $now)""".trimIndent()
                    )
                    // 默认情绪状态
                    db.execSQL(
                        """INSERT OR IGNORE INTO emotional_state
                            (agentId, valence, arousal, potentialEnergy, lastEmotion, lastUserInteractionAt, lastDecayAt, createdAt, updatedAt)
                            VALUES ($id, 0.0, 0.3, 0, NULL, $now, $now, $now, $now)""".trimIndent()
                    )
                }
            }
        }

        /**
         * 为每个 Agent 创建首次见面状态：
         * - 拥有旧聊天记录的 Agent → COMPLETED_WITHOUT_NICKNAME
         * - 无聊天记录的 Agent → NOT_STARTED
         */
        private fun createFirstMeetingStates(db: SupportSQLiteDatabase, ownerAgentId: Long, now: Long) {
            db.query("SELECT id FROM agent_config").use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val hasChat = if (id == ownerAgentId) {
                        db.query("SELECT COUNT(*) FROM chat_messages WHERE agentId = $id").use { cc ->
                            cc.moveToFirst() && cc.getInt(0) > 0
                        }
                    } else {
                        false
                    }
                    val phase = if (hasChat) "COMPLETED_WITHOUT_NICKNAME" else "NOT_STARTED"
                    val completedAt = if (hasChat) now.toString() else "NULL"
                    db.execSQL(
                        """INSERT INTO first_meeting_state
                            (agentId, phase, greetingMessageId, greetingSentAt, userReplyCount, followUpAsked, nicknameCaptured, completedAt, updatedAt)
                            VALUES ($id, '$phase', NULL, NULL, 0, 0, 0, $completedAt, $now)""".trimIndent()
                    )
                }
            }
        }

        /**
         * 通用表重建模式：创建 _new 表 → 复制数据 → 删除旧表 → 重命名。
         */
        private fun rebuildTable(
            db: SupportSQLiteDatabase,
            tableName: String,
            createNewSql: String,
            copySql: String
        ) {
            db.execSQL(createNewSql)
            db.execSQL(copySql)
            db.execSQL("DROP TABLE $tableName")
            db.execSQL("ALTER TABLE ${tableName}_new RENAME TO $tableName")
        }
    }

    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE commitments ADD COLUMN claimedAt INTEGER")
            db.execSQL("ALTER TABLE commitments ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE commitments ADD COLUMN nextRetryAt INTEGER")
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN batchId TEXT")
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN claimedAt INTEGER")
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // 全部已注册迁移（按版本顺序）
    // 必须放在所有 MIGRATION_x_y 声明之后，因为 Kotlin object 属性按声明顺序初始化
    // ───────────────────────────────────────────────────────────────────────────
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16
    )
}
