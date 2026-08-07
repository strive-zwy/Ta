package com.agent.ta.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 14→15 迁移测试（Task 4）
 *
 * 验证：
 * 1. 迁移后行数、原 ID、消息引用和字段值不变
 * 2. 全部旧业务数据归属确定的 active agent
 * 3. 两个 Agent 可保存相同日期的作息和相同描述的未来事件
 * 4. 已有聊天的 Agent 不会升级后重新问候（COMPLETED_WITHOUT_NICKNAME）
 */
@RunWith(AndroidJUnit4::class)
class Migration14To15Test {

    companion object {
        private const val TEST_DB = "migration-test.db"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TaDatabase::class.java
    )

    @Test
    fun migrate_14_to_15_preserves_data_and_assigns_agentId() {
        // ── 构造 v14 数据库 ──
        val db = helper.createDatabase(TEST_DB, 14)
        val now = System.currentTimeMillis()

        // 两个 Agent 配置：A 激活（最近导入），B 未激活
        db.execSQL(
            "INSERT INTO agent_config (id, configJson, agentName, importedAt, isActive) " +
                "VALUES (10, '{\"agent\":{\"name\":\"A\"}}', 'A', $now, 1)"
        )
        db.execSQL(
            "INSERT INTO agent_config (id, configJson, agentName, importedAt, isActive) " +
                "VALUES (20, '{\"agent\":{\"name\":\"B\"}}', 'B', ${now - 1000}, 0)"
        )

        // 聊天消息（属于旧全局数据，迁移后应归 A）
        db.execSQL(
            "INSERT INTO chat_messages (id, direction, text, state, status, createdAt) " +
                "VALUES (100, 'inbound', '你好', 'normal', 'replied', $now)"
        )
        db.execSQL(
            "INSERT INTO chat_messages (id, direction, text, state, status, createdAt) " +
                "VALUES (101, 'outbound', '嗨~', 'normal', 'sent', $now)"
        )

        // 记忆
        db.execSQL(
            "INSERT INTO memories (id, type, category, content, importance, source, createdAt, updatedAt, accessCount) " +
                "VALUES (200, 'event', '工作', '一起加班', 4, 'chat', $now, $now, 0)"
        )

        // 摘要
        db.execSQL(
            "INSERT INTO conversation_summaries (id, bucketId, startMessageId, endMessageId, summary, createdAt, messageCount) " +
                "VALUES (300, 1, 100, 101, '初次见面', $now, 2)"
        )

        // 作息
        db.execSQL(
            "INSERT INTO daily_schedule (date, slotsJson, originalSlotsJson, isAdjusted, source, createdAt, updatedAt) " +
                "VALUES ('2026-08-06', '[]', '[]', 0, 'plan', $now, $now)"
        )

        // 未来事件
        db.execSQL(
            "INSERT INTO future_events (id, date, description, source, consumed, createdAt) " +
                "VALUES (400, '2026-08-10', '演唱会', 'chat', 0, $now)"
        )

        // 每日状态
        db.execSQL(
            "INSERT INTO daily_state (date, sleepTime, wakeTime, sleepDurationMin, mood, fatigue, stress, energy, " +
                "mainActivities, specialEvents, hadInteractionWithUser, interactionCount, summary, createdAt, updatedAt) " +
                "VALUES ('2026-08-05', '01:00', '08:00', 420, 0.5, 0.3, 0.2, 0.8, '[]', '[]', 1, 10, '开心的一天', $now, $now)"
        )

        // 承诺
        db.execSQL(
            "INSERT INTO commitments (id, type, content, participants, triggerAt, deadline, status, source, relatedMessageId, createdAt, updatedAt) " +
                "VALUES (500, 'promise', '明天叫你起床', 'agent,user', $now, NULL, 'pending', 'chat', 100, $now, $now)"
        )

        // 关系状态（旧：id=1）
        db.execSQL(
            "INSERT INTO relationship_state (id, currentStage, intimacyScore, trustScore, interactionCount, lastInteractionAt, lastDecayAt, createdAt, updatedAt) " +
                "VALUES (1, 'acquaintance', 20, 12, 30, $now, $now, $now, $now)"
        )

        // 情绪状态（旧：id=1）
        db.execSQL(
            "INSERT INTO emotional_state (id, valence, arousal, potentialEnergy, lastEmotion, lastUserInteractionAt, lastDecayAt, createdAt, updatedAt) " +
                "VALUES (1, 0.5, 0.6, 30, 'happy', $now, $now, $now, $now)"
        )

        // 里程碑
        db.execSQL(
            "INSERT INTO milestone_events (id, type, title, triggeredAt, triggerSource, contextSnapshot) " +
                "VALUES (600, 'first_vulnerability', '第一次袒露脆弱', $now, 'llm_declared', '{}')"
        )

        // 状态日志
        db.execSQL(
            "INSERT INTO state_log (id, state, enteredAt, exitedAt) VALUES (700, 'normal', $now, $now)"
        )

        db.close()

        // ── 执行迁移并验证 schema ──
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 15, false, DatabaseMigrations.MIGRATION_14_15)

        // 1. 行数和原 ID 保持不变
        migrated.query("SELECT COUNT(*) FROM chat_messages").use { c ->
            c.moveToFirst(); assertEquals("聊天消息行数不变", 2, c.getInt(0))
        }
        migrated.query("SELECT id FROM chat_messages ORDER BY id").use { c ->
            c.moveToFirst(); assertEquals(100, c.getLong(0))
            c.moveToNext(); assertEquals(101, c.getLong(0))
        }
        migrated.query("SELECT COUNT(*) FROM memories").use { c ->
            c.moveToFirst(); assertEquals("记忆行数不变", 1, c.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM conversation_summaries").use { c ->
            c.moveToFirst(); assertEquals("摘要行数不变", 1, c.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM commitments").use { c ->
            c.moveToFirst(); assertEquals("承诺行数不变", 1, c.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM milestone_events").use { c ->
            c.moveToFirst(); assertEquals("里程碑行数不变", 1, c.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM state_log").use { c ->
            c.moveToFirst(); assertEquals("状态日志行数不变", 1, c.getInt(0))
        }

        // 2. 全部旧业务数据归属确定的 active agent（id=10）
        migrated.query("SELECT agentId FROM chat_messages WHERE id = 100").use { c ->
            c.moveToFirst(); assertEquals("消息归属 agent A", 10L, c.getLong(0))
        }
        migrated.query("SELECT agentId FROM memories WHERE id = 200").use { c ->
            c.moveToFirst(); assertEquals("记忆归属 agent A", 10L, c.getLong(0))
        }
        migrated.query("SELECT agentId FROM daily_schedule WHERE date = '2026-08-06'").use { c ->
            c.moveToFirst(); assertEquals("作息归属 agent A", 10L, c.getLong(0))
        }
        migrated.query("SELECT agentId FROM future_events WHERE id = 400").use { c ->
            c.moveToFirst(); assertEquals("未来事件归属 agent A", 10L, c.getLong(0))
        }
        migrated.query("SELECT agentId FROM commitments WHERE id = 500").use { c ->
            c.moveToFirst(); assertEquals("承诺归属 agent A", 10L, c.getLong(0))
        }
        migrated.query("SELECT agentId FROM milestone_events WHERE id = 600").use { c ->
            c.moveToFirst(); assertEquals("里程碑归属 agent A", 10L, c.getLong(0))
        }

        // 关系和情绪状态迁移到 agent A，保留原数值
        migrated.query("SELECT agentId, currentStage, intimacyScore FROM relationship_state WHERE agentId = 10").use { c ->
            c.moveToFirst()
            assertEquals(10L, c.getLong(0))
            assertEquals("acquaintance", c.getString(1))
            assertEquals(20, c.getInt(2))
        }
        migrated.query("SELECT agentId, valence, potentialEnergy FROM emotional_state WHERE agentId = 10").use { c ->
            c.moveToFirst()
            assertEquals(10L, c.getLong(0))
            assertEquals(0.5f, c.getFloat(1), 0.001f)
            assertEquals(30, c.getInt(2))
        }

        // 3. 其他历史 Agent（B=20）有独立默认关系和情绪记录
        migrated.query("SELECT agentId, currentStage, intimacyScore FROM relationship_state WHERE agentId = 20").use { c ->
            c.moveToFirst()
            assertEquals(20L, c.getLong(0))
            assertEquals("stranger", c.getString(1))
            assertEquals("Agent B 初始 intimacy 应为 0", 0, c.getInt(2))
        }
        migrated.query("SELECT agentId, valence FROM emotional_state WHERE agentId = 20").use { c ->
            c.moveToFirst()
            assertEquals(20L, c.getLong(0))
            assertEquals(0.0f, c.getFloat(1), 0.001f)
        }

        // 4. 已有聊天的 Agent A → COMPLETED_WITHOUT_NICKNAME（不会重新问候）
        migrated.query("SELECT phase FROM first_meeting_state WHERE agentId = 10").use { c ->
            c.moveToFirst()
            assertEquals("有聊天的 Agent 应为 COMPLETED_WITHOUT_NICKNAME", "COMPLETED_WITHOUT_NICKNAME", c.getString(0))
        }
        // Agent B 无聊天 → NOT_STARTED
        migrated.query("SELECT phase FROM first_meeting_state WHERE agentId = 20").use { c ->
            c.moveToFirst()
            assertEquals("无聊天的 Agent 应为 NOT_STARTED", "NOT_STARTED", c.getString(0))
        }

        // 5. 两个 Agent 可保存相同日期的作息（复合主键 agentId+date）
        migrated.execSQL(
            "INSERT INTO daily_schedule (agentId, date, slotsJson, originalSlotsJson, isAdjusted, source, createdAt, updatedAt) " +
                "VALUES (20, '2026-08-06', '[]', '[]', 0, 'plan', $now, $now)"
        )
        migrated.query("SELECT COUNT(*) FROM daily_schedule WHERE date = '2026-08-06'").use { c ->
            c.moveToFirst(); assertEquals("两个 Agent 同一天各有作息", 2, c.getInt(0))
        }

        // 6. 两个 Agent 可保存相同描述的未来事件
        migrated.execSQL(
            "INSERT INTO future_events (agentId, date, description, source, consumed, createdAt) " +
                "VALUES (20, '2026-08-10', '演唱会', 'chat', 0, $now)"
        )
        migrated.query("SELECT COUNT(*) FROM future_events WHERE date = '2026-08-10' AND description = '演唱会'").use { c ->
            c.moveToFirst(); assertEquals("两个 Agent 可有相同事件", 2, c.getInt(0))
        }

        migrated.close()
    }
}
