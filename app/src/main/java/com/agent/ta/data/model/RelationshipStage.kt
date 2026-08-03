package com.agent.ta.data.model

/**
 * Agent 与用户的关系阶段（5 阶段）
 *
 * 阶段边界：
 * - STRANGER：0-15（陌生）
 * - ACQUAINTANCE：16-35（初识）
 * - FAMILIAR：36-60（熟悉）
 * - INTIMATE：61-85（亲密）
 * - CONFIDANT：86-100（知己）
 */
enum class RelationshipStage(
    val id: String,
    val displayName: String,
    val scoreRange: IntRange
) {
    STRANGER("stranger", "陌生", 0..15),
    ACQUAINTANCE("acquaintance", "初识", 16..35),
    FAMILIAR("familiar", "熟悉", 36..60),
    INTIMATE("intimate", "亲密", 61..85),
    CONFIDANT("confidant", "知己", 86..100);

    companion object {
        /**
         * 按 intimacy score 返回对应阶段
         */
        fun fromScore(score: Int): RelationshipStage {
            val clamped = score.coerceIn(0, 100)
            return entries.first { clamped in it.scoreRange }
        }

        /**
         * 按 id 字符串解析阶段
         */
        fun fromId(id: String): RelationshipStage? {
            return entries.find { it.id == id }
        }
    }
}
