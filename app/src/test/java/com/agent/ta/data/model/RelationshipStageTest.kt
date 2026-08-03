package com.agent.ta.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RelationshipStage 枚举测试（Phase 2 关系系统 Step 17）
 */
class RelationshipStageTest {

    @Test
    fun fromScore_zero_returns_stranger() {
        assertEquals(RelationshipStage.STRANGER, RelationshipStage.fromScore(0))
    }

    @Test
    fun fromScore_fifteen_returns_stranger() {
        assertEquals(RelationshipStage.STRANGER, RelationshipStage.fromScore(15))
    }

    @Test
    fun fromScore_sixteen_returns_acquaintance() {
        assertEquals(RelationshipStage.ACQUAINTANCE, RelationshipStage.fromScore(16))
    }

    @Test
    fun fromScore_fifty_six_returns_familiar() {
        assertEquals(RelationshipStage.FAMILIAR, RelationshipStage.fromScore(56))
    }

    @Test
    fun fromScore_sixty_one_returns_intimate() {
        assertEquals(RelationshipStage.INTIMATE, RelationshipStage.fromScore(61))
    }

    @Test
    fun fromScore_hundred_returns_confidant() {
        assertEquals(RelationshipStage.CONFIDANT, RelationshipStage.fromScore(100))
    }

    @Test
    fun fromScore_negative_clamps_to_stranger() {
        assertEquals(RelationshipStage.STRANGER, RelationshipStage.fromScore(-5))
    }

    @Test
    fun fromScore_over_hundred_clamps_to_confidant() {
        assertEquals(RelationshipStage.CONFIDANT, RelationshipStage.fromScore(150))
    }

    @Test
    fun fromId_acquaintance_returns_correct_enum() {
        assertEquals(RelationshipStage.ACQUAINTANCE, RelationshipStage.fromId("acquaintance"))
    }

    @Test
    fun fromId_unknown_returns_null() {
        assertEquals(null, RelationshipStage.fromId("unknown"))
    }
}
