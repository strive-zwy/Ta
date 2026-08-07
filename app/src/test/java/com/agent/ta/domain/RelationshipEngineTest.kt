package com.agent.ta.domain

import com.agent.ta.data.local.entity.RelationshipStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * RelationshipEngine 单元测试（Phase 2 关系系统 Step 16）
 *
 * 验证：
 * - applyTurnEnd 增量计算（情绪加权 + 长度加权）
 * - applyDailyDecay 衰减逻辑
 * - checkStageTransition 阶段边界判定
 */
class RelationshipEngineTest {

    private lateinit var engine: RelationshipEngine

    @Before
    fun setup() {
        engine = RelationshipEngine()
    }

    private fun makeState(intimacy: Int = 0, trust: Int = 0, interactionCount: Int = 0): RelationshipStateEntity {
        val now = System.currentTimeMillis()
        return RelationshipStateEntity(
            agentId = 1L,
            currentStage = "stranger",
            intimacyScore = intimacy,
            trustScore = trust,
            interactionCount = interactionCount,
            lastInteractionAt = now,
            lastDecayAt = now,
            createdAt = now,
            updatedAt = now
        )
    }

    @Test
    fun applyTurnEnd_neutral_short_message_returns_base_increment() {
        // emotion="neutral", messageLength=30 → intimacy 增量 = 0.5 × 1.0 × 1.0 = 0.5
        val ctx = RelationshipEngine.TurnContext(
            emotion = "neutral",
            isUserInitiated = true,
            messageLength = 30
        )
        val state = makeState(intimacy = 0, trust = 0, interactionCount = 0)
        val update = engine.applyTurnEnd(ctx, state)

        assertEquals(0.5, update.intimacyIncrement, 0.001)
        assertEquals(0.3, update.trustIncrement, 0.001)  // 0.5 × 0.6 = 0.3
        assertEquals(1, update.newInteractionCount)
        assertNull(update.stageTransition)  // 0 + 0.5 = 0.5 取整 0，未跨阶段
    }

    @Test
    fun applyTurnEnd_happy_long_message_gets_weighted_increment() {
        // emotion="happy", messageLength=100 → 0.5 × 1.5 × 1.2 = 0.9
        val ctx = RelationshipEngine.TurnContext(
            emotion = "happy",
            isUserInitiated = true,
            messageLength = 100
        )
        val state = makeState(intimacy = 0, trust = 0, interactionCount = 5)
        val update = engine.applyTurnEnd(ctx, state)

        assertEquals(0.9, update.intimacyIncrement, 0.001)
        assertEquals(0.54, update.trustIncrement, 0.001)  // 0.9 × 0.6 = 0.54
        assertEquals(6, update.newInteractionCount)
    }

    @Test
    fun applyTurnEnd_angry_reduces_increment() {
        // emotion="angry" → 0.5 × 0.3 × 1.0 = 0.15
        val ctx = RelationshipEngine.TurnContext(
            emotion = "angry",
            isUserInitiated = true,
            messageLength = 20
        )
        val state = makeState(intimacy = 10, trust = 5, interactionCount = 2)
        val update = engine.applyTurnEnd(ctx, state)

        assertEquals(0.15, update.intimacyIncrement, 0.001)
        assertEquals(0.09, update.trustIncrement, 0.001)  // 0.15 × 0.6 = 0.09
    }

    @Test
    fun applyTurnEnd_vulnerable_emotion_gets_highest_weight() {
        // emotion="vulnerable" → 0.5 × 2.0 × 1.5 (msg>200) = 1.5
        val ctx = RelationshipEngine.TurnContext(
            emotion = "vulnerable",
            isUserInitiated = true,
            messageLength = 250
        )
        val state = makeState(intimacy = 14, trust = 8, interactionCount = 10)
        val update = engine.applyTurnEnd(ctx, state)

        assertEquals(1.5, update.intimacyIncrement, 0.001)
        // 14 + 1.5 = 15.5 → 取整 15（仍在 STRANGER 0-15 区间内，但若取整策略不同可能跨边界）
        // 此处只验证增量，阶段切换由 checkStageTransition 单独测试
    }

    @Test
    fun applyDailyDecay_reduces_trust_and_intimacy() {
        val state = makeState(intimacy = 50, trust = 50, interactionCount = 30)
        val newState = engine.applyDailyDecay(state)

        // trust -0.5 → 49.5 取整 49；intimacy -0.2 → 49.8 取整 49
        // 注意 Kotlin toInt() 是向 0 截断，49.5 → 49
        assertEquals(49, newState.trustScore)
        assertEquals(49, newState.intimacyScore)
    }

    @Test
    fun applyDailyDecay_clamps_to_zero() {
        val state = makeState(intimacy = 0, trust = 0, interactionCount = 0)
        val newState = engine.applyDailyDecay(state)

        assertEquals(0, newState.trustScore)
        assertEquals(0, newState.intimacyScore)
    }

    @Test
    fun checkStageTransition_crossing_boundary_returns_new_stage() {
        // oldScore=15 (STRANGER), newScore=16 (ACQUAINTANCE) → 跨边界
        val transition1 = engine.checkStageTransition(15, 16)
        assertEquals(com.agent.ta.data.model.RelationshipStage.ACQUAINTANCE, transition1)

        // oldScore=60 (FAMILIAR), newScore=61 (INTIMATE) → 跨边界
        val transition2 = engine.checkStageTransition(60, 61)
        assertEquals(com.agent.ta.data.model.RelationshipStage.INTIMATE, transition2)
    }

    @Test
    fun checkStageTransition_no_boundary_returns_null() {
        // 同阶段内分数变化 → null
        val noTransition = engine.checkStageTransition(50, 55)
        assertNull(noTransition)

        // 分数相同 → null
        val sameScore = engine.checkStageTransition(30, 30)
        assertNull(sameScore)
    }
}
