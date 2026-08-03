package com.agent.ta.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agent.ta.di.ServiceLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RelationshipService 集成测试（Phase 2 关系系统 Step 18）
 *
 * 验证：
 * 1. getCurrentState 首次调用自动初始化为 stranger（scores=0）
 * 2. onTurnCompleted 推进 intimacy/trust 数值
 * 3. recordMilestone 写入 milestone_events 表
 * 4. recordMilestone 24h 内同 type 去重
 *
 * 运行环境：emulator（需要 ServiceLocator + Room DB 初始化）
 */
@RunWith(AndroidJUnit4::class)
class RelationshipServiceTest {

    private lateinit var service: RelationshipService

    @Before
    fun setup() {
        // ServiceLocator 在 Application.onCreate 中初始化，androidTest 已完成此过程
        // 清理 relationship_state 和 milestone_events 表，确保每个测试从干净状态开始
        val db = ServiceLocator.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM relationship_state")
        db.execSQL("DELETE FROM milestone_events")
        service = RelationshipService()
    }

    @Test
    fun getCurrentState_first_call_initializes_stranger_state() = runBlocking {
        val state = service.getCurrentState()

        assertEquals(
            "首次调用应初始化为 stranger 阶段",
            "stranger",
            state.currentStage
        )
        assertEquals("初始 intimacyScore 应为 0", 0, state.intimacyScore)
        assertEquals("初始 trustScore 应为 0", 0, state.trustScore)
        assertEquals("初始 interactionCount 应为 0", 0, state.interactionCount)
    }

    @Test
    fun onTurnCompleted_increments_intimacy_and_trust() = runBlocking {
        // 先确保初始化
        val before = service.getCurrentState()
        assertEquals("前置：初始 intimacy 应为 0", 0, before.intimacyScore)

        // emotion=happy（×1.5）, messageLength=80（>50 → ×1.2）, 用户主动
        // 期望 intimacy 增量 = 0.5 × 1.5 × 1.2 = 0.9
        service.onTurnCompleted(
            emotion = "happy",
            isUserInitiated = true,
            messageLength = 80
        )

        val after = service.getCurrentState()
        assertTrue(
            "onTurnCompleted 后 intimacyScore 应 > 0，实际=${after.intimacyScore}",
            after.intimacyScore > 0
        )
        assertTrue(
            "onTurnCompleted 后 trustScore 应 > 0，实际=${after.trustScore}",
            after.trustScore > 0
        )
        assertEquals(
            "interactionCount 应 +1",
            before.interactionCount + 1,
            after.interactionCount
        )
    }

    @Test
    fun recordMilestone_inserts_to_milestone_events_table() = runBlocking {
        val inserted = service.recordMilestone(
            type = "first_vulnerability",
            title = "测试里程碑",
            source = "llm_declared",
            context = mapOf("replyText" to "我其实有点孤单")
        )

        assertTrue("首次写入应返回 true", inserted)

        val recent = service.getRecentMilestones(5)
        assertTrue("里程碑列表应非空", recent.isNotEmpty())
        assertEquals(
            "应包含刚写入的 type",
            "first_vulnerability",
            recent.first().type
        )
        assertEquals(
            "title 应为传入的值",
            "测试里程碑",
            recent.first().title
        )
        assertEquals(
            "triggerSource 应为传入的值",
            "llm_declared",
            recent.first().triggerSource
        )
    }

    @Test
    fun recordMilestone_dedup_skips_same_type_within_24h() = runBlocking {
        val firstInsert = service.recordMilestone(
            type = "first_argument",
            title = "第一次争吵",
            source = "llm_declared",
            context = emptyMap()
        )
        assertTrue("首次写入应成功", firstInsert)

        val secondInsert = service.recordMilestone(
            type = "first_argument",
            title = "第一次争吵-重复",
            source = "llm_declared",
            context = emptyMap()
        )
        assertFalse(
            "24h 内同 type 第二次写入应被去重跳过",
            secondInsert
        )

        val recent = service.getRecentMilestones(5)
        assertEquals(
            "去重后应只有 1 条记录",
            1,
            recent.size
        )
        assertEquals(
            "保留的应是首次写入的 title",
            "第一次争吵",
            recent.first().title
        )
    }
}
