package com.agent.ta.domain.firstmeeting

import com.agent.ta.data.remote.dto.NicknameResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NicknameResolver 单元测试（Task 14）
 *
 * 验证：
 * 1. 解析：缺字段、未知 intent、越界 confidence、恶意格式
 * 2. 保存判定：intent / shouldSave / confidence / 本地校验 四道关卡
 * 3. 清空判定：CLEAR intent 或本地清空意图
 * 4. 确认判定：SELF_INTRODUCTION 不直接保存
 */
class NicknameResolutionTest {

    private val resolver = NicknameResolver

    // ═══════════════════════════════════════════════════════════════════════
    // parse：解析 LLM 输出的 NicknameResolution
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun parse_null_returns_NONE_resolution() {
        val result = resolver.parse(null)
        assertEquals("NONE", result.intent)
        assertEquals(0f, result.confidence)
        assertNull(result.nickname)
        assertFalse(result.shouldSave)
    }

    @Test
    fun parse_missing_intent_defaults_to_NONE() {
        val raw = NicknameResolution(
            intent = "",
            nickname = "阿哲",
            confidence = 0.9f,
            shouldSave = true
        )
        val result = resolver.parse(raw)
        assertEquals("空 intent 应默认为 NONE", "NONE", result.intent)
    }

    @Test
    fun parse_unknown_intent_defaults_to_NONE() {
        val raw = NicknameResolution(
            intent = "HACKED_INTENT",
            nickname = "阿哲",
            confidence = 0.9f,
            shouldSave = true
        )
        val result = resolver.parse(raw)
        assertEquals("未知 intent 应默认为 NONE", "NONE", result.intent)
    }

    @Test
    fun parse_valid_intents_preserved() {
        val validIntents = listOf(
            "EXPLICIT_NICKNAME", "SELF_INTRODUCTION", "DECLINED",
            "AMBIGUOUS", "CORRECTION", "CLEAR", "NONE"
        )
        for (intent in validIntents) {
            val raw = NicknameResolution(intent = intent, confidence = 0.5f)
            val result = resolver.parse(raw)
            assertEquals("intent=$intent 应被保留", intent, result.intent)
        }
    }

    @Test
    fun parse_confidence_above_1_clamped_to_1() {
        val raw = NicknameResolution(intent = "EXPLICIT_NICKNAME", confidence = 1.5f)
        val result = resolver.parse(raw)
        assertEquals("confidence > 1 应被钳制为 1", 1f, result.confidence, 0.001f)
    }

    @Test
    fun parse_confidence_below_0_clamped_to_0() {
        val raw = NicknameResolution(intent = "EXPLICIT_NICKNAME", confidence = -0.5f)
        val result = resolver.parse(raw)
        assertEquals("confidence < 0 应被钳制为 0", 0f, result.confidence, 0.001f)
    }

    @Test
    fun parse_nan_confidence_clamped_to_0() {
        val raw = NicknameResolution(intent = "EXPLICIT_NICKNAME", confidence = Float.NaN)
        val result = resolver.parse(raw)
        assertEquals("NaN confidence 应被钳制为 0", 0f, result.confidence, 0.001f)
    }

    @Test
    fun parse_infinity_confidence_clamped_to_1() {
        val raw = NicknameResolution(intent = "EXPLICIT_NICKNAME", confidence = Float.POSITIVE_INFINITY)
        val result = resolver.parse(raw)
        assertEquals("正无穷 confidence 应被钳制为 1", 1f, result.confidence, 0.001f)
    }

    @Test
    fun parse_preserves_nickname_and_evidence() {
        val raw = NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "阿哲",
            confidence = 0.9f,
            evidence = "用户说'叫我阿哲'"
        )
        val result = resolver.parse(raw)
        assertEquals("阿哲", result.nickname)
        assertEquals("用户说'叫我阿哲'", result.evidence)
    }

    @Test
    fun parse_unknown_intent_resets_shouldSave_to_false() {
        val raw = NicknameResolution(
            intent = "UNKNOWN",
            nickname = "阿哲",
            confidence = 0.99f,
            shouldSave = true
        )
        val result = resolver.parse(raw)
        assertFalse("未知 intent 时 shouldSave 应被重置为 false", result.shouldSave)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // decideSave：保存判定
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun decideSave_explicit_nickname_high_confidence_saves() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "叫我阿哲",
            confidence = 0.9f,
            shouldSave = true
        ))
        val decision = resolver.decideSave(resolution)
        assertTrue("EXPLICIT_NICKNAME + 高 confidence + shouldSave 应保存", decision.shouldSave)
        assertEquals("应保存清洗后的称呼", "阿哲", decision.normalizedNickname)
    }

    @Test
    fun decideSave_correction_high_confidence_saves() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "CORRECTION",
            nickname = "阿哲",
            confidence = 0.95f,
            shouldSave = true
        ))
        val decision = resolver.decideSave(resolution)
        assertTrue("CORRECTION + 高 confidence 应保存", decision.shouldSave)
        assertEquals("阿哲", decision.normalizedNickname)
    }

    @Test
    fun decideSave_self_introduction_does_not_save() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "SELF_INTRODUCTION",
            nickname = "张明",
            confidence = 0.95f,
            shouldSave = true
        ))
        val decision = resolver.decideSave(resolution)
        assertFalse("SELF_INTRODUCTION 不应直接保存", decision.shouldSave)
    }

    @Test
    fun decideSave_declined_does_not_save() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "DECLINED",
            confidence = 0.95f
        ))
        val decision = resolver.decideSave(resolution)
        assertFalse("DECLINED 不应保存", decision.shouldSave)
    }

    @Test
    fun decideSave_ambiguous_does_not_save() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "AMBIGUOUS",
            confidence = 0.5f
        ))
        val decision = resolver.decideSave(resolution)
        assertFalse("AMBIGUOUS 不应保存", decision.shouldSave)
    }

    @Test
    fun decideSave_none_does_not_save() {
        val resolution = resolver.parse(NicknameResolution(intent = "NONE"))
        val decision = resolver.decideSave(resolution)
        assertFalse("NONE 不应保存", decision.shouldSave)
    }

    @Test
    fun decideSave_low_confidence_does_not_save() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "阿哲",
            confidence = 0.7f,  // 低于 0.85
            shouldSave = true
        ))
        val decision = resolver.decideSave(resolution)
        assertFalse("confidence < 0.85 不应保存", decision.shouldSave)
        assertTrue("原因应提及 confidence", decision.reason.contains("confidence") || decision.reason.contains("置信度"))
    }

    @Test
    fun decideSave_shouldSave_false_does_not_save() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "阿哲",
            confidence = 0.95f,
            shouldSave = false
        ))
        val decision = resolver.decideSave(resolution)
        assertFalse("shouldSave=false 不应保存", decision.shouldSave)
    }

    @Test
    fun decideSave_null_nickname_does_not_save() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = null,
            confidence = 0.95f,
            shouldSave = true
        ))
        val decision = resolver.decideSave(resolution)
        assertFalse("nickname 为 null 不应保存", decision.shouldSave)
    }

    @Test
    fun decideSave_blank_nickname_does_not_save() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "   ",
            confidence = 0.95f,
            shouldSave = true
        ))
        val decision = resolver.decideSave(resolution)
        assertFalse("nickname 为空白不应保存", decision.shouldSave)
    }

    @Test
    fun decideSave_url_nickname_rejected_by_validator() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "https://evil.com",
            confidence = 0.95f,
            shouldSave = true
        ))
        val decision = resolver.decideSave(resolution)
        assertFalse("URL 称呼应被本地校验拒绝", decision.shouldSave)
        assertTrue("原因应提及校验失败", decision.reason.contains("校验") || decision.reason.contains("URL"))
    }

    @Test
    fun decideSave_pure_emoji_nickname_rejected_by_validator() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "😊🎉",
            confidence = 0.95f,
            shouldSave = true
        ))
        val decision = resolver.decideSave(resolution)
        assertFalse("纯 emoji 称呼应被本地校验拒绝", decision.shouldSave)
    }

    @Test
    fun decideSave_too_long_nickname_rejected_by_validator() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "哈哈哈哈哈哈哈哈哈哈哈哈哈",  // 13 字
            confidence = 0.95f,
            shouldSave = true
        ))
        val decision = resolver.decideSave(resolution)
        assertFalse("超长称呼应被本地校验拒绝", decision.shouldSave)
    }

    @Test
    fun decideSave_meaningless_nickname_rejected_by_validator() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "随便",
            confidence = 0.95f,
            shouldSave = true
        ))
        val decision = resolver.decideSave(resolution)
        assertFalse("无意义称呼应被本地校验拒绝", decision.shouldSave)
    }

    @Test
    fun decideSave_strips_modifiers_from_nickname() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "叫我阿哲就行",
            confidence = 0.95f,
            shouldSave = true
        ))
        val decision = resolver.decideSave(resolution)
        assertTrue("应保存", decision.shouldSave)
        assertEquals("应保存清洗后的称呼", "阿哲", decision.normalizedNickname)
    }

    @Test
    fun decideSave_boundary_confidence_0_85_saves() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "阿哲",
            confidence = 0.85f,  // 恰好等于阈值
            shouldSave = true
        ))
        val decision = resolver.decideSave(resolution)
        assertTrue("confidence = 0.85 应保存（边界）", decision.shouldSave)
    }

    @Test
    fun decideSave_below_boundary_confidence_0_84_does_not_save() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "阿哲",
            confidence = 0.84f,  // 低于阈值
            shouldSave = true
        ))
        val decision = resolver.decideSave(resolution)
        assertFalse("confidence = 0.84 不应保存（边界）", decision.shouldSave)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // shouldClear：清空判定
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun shouldClear_CLEAR_intent_returns_true() {
        val resolution = resolver.parse(NicknameResolution(intent = "CLEAR"))
        assertTrue("CLEAR intent 应清空", resolver.shouldClear(resolution))
    }

    @Test
    fun shouldClear_EXPLICIT_NICKNAME_returns_false() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "阿哲"
        ))
        assertFalse("EXPLICIT_NICKNAME 不应清空", resolver.shouldClear(resolution))
    }

    @Test
    fun shouldClear_nickname_with_clear_phrase_returns_true() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "直接叫你就行",
            shouldSave = true
        ))
        assertTrue("nickname 含清空短语应清空", resolver.shouldClear(resolution))
    }

    @Test
    fun shouldClear_NONE_returns_false() {
        val resolution = resolver.parse(NicknameResolution(intent = "NONE"))
        assertFalse("NONE 不应清空", resolver.shouldClear(resolution))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // needsConfirmation：确认判定（SELF_INTRODUCTION）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun needsConfirmation_SELF_INTRODUCTION_returns_true() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "SELF_INTRODUCTION",
            nickname = "张明"
        ))
        assertTrue("SELF_INTRODUCTION 需要确认", resolver.needsConfirmation(resolution))
    }

    @Test
    fun needsConfirmation_EXPLICIT_NICKNAME_returns_false() {
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "阿哲"
        ))
        assertFalse("EXPLICIT_NICKNAME 不需要确认", resolver.needsConfirmation(resolution))
    }

    @Test
    fun needsConfirmation_NONE_returns_false() {
        val resolution = resolver.parse(NicknameResolution(intent = "NONE"))
        assertFalse("NONE 不需要确认", resolver.needsConfirmation(resolution))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 集成场景
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun scenario_user_says_call_me_azhe() {
        // 用户说"叫我阿哲"
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "阿哲",
            confidence = 0.95f,
            evidence = "用户明确说'叫我阿哲'",
            shouldSave = true
        ))
        val decision = resolver.decideSave(resolution)
        assertTrue("应保存", decision.shouldSave)
        assertEquals("阿哲", decision.normalizedNickname)
        assertFalse("不需要确认", resolver.needsConfirmation(resolution))
        assertFalse("不是清空", resolver.shouldClear(resolution))
    }

    @Test
    fun scenario_user_introduces_full_name() {
        // 用户说"我叫张明"
        val resolution = resolver.parse(NicknameResolution(
            intent = "SELF_INTRODUCTION",
            nickname = "张明",
            confidence = 0.9f,
            evidence = "用户说'我叫张明'",
            shouldSave = false
        ))
        val decision = resolver.decideSave(resolution)
        assertFalse("SELF_INTRODUCTION 不直接保存", decision.shouldSave)
        assertTrue("需要 Agent 确认", resolver.needsConfirmation(resolution))
    }

    @Test
    fun scenario_user_wants_specific_nickname_from_full_name() {
        // 用户说"我叫张明，你叫我明哥"
        val resolution = resolver.parse(NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "明哥",
            confidence = 0.95f,
            evidence = "用户说'你叫我明哥'",
            shouldSave = true
        ))
        val decision = resolver.decideSave(resolution)
        assertTrue("应保存'明哥'", decision.shouldSave)
        assertEquals("明哥", decision.normalizedNickname)
    }

    @Test
    fun scenario_user_corrects_nickname() {
        // 用户说"别叫我宝宝了，叫我阿哲"
        val resolution = resolver.parse(NicknameResolution(
            intent = "CORRECTION",
            nickname = "阿哲",
            confidence = 0.95f,
            evidence = "用户要求纠正为'阿哲'",
            shouldSave = true
        ))
        val decision = resolver.decideSave(resolution)
        assertTrue("CORRECTION 应保存", decision.shouldSave)
        assertEquals("阿哲", decision.normalizedNickname)
    }

    @Test
    fun scenario_user_clears_nickname() {
        // 用户说"直接叫你就行"
        val resolution = resolver.parse(NicknameResolution(
            intent = "CLEAR",
            confidence = 0.95f,
            evidence = "用户要求清空称呼"
        ))
        assertTrue("应清空", resolver.shouldClear(resolution))
        assertFalse("不应保存", resolver.decideSave(resolution).shouldSave)
    }

    @Test
    fun scenario_user_declines() {
        // 用户说"不想告诉你"
        val resolution = resolver.parse(NicknameResolution(
            intent = "DECLINED",
            confidence = 0.9f
        ))
        assertFalse("DECLINED 不保存", resolver.decideSave(resolution).shouldSave)
        assertFalse("DECLINED 不清空", resolver.shouldClear(resolution))
        assertFalse("DECLINED 不需要确认", resolver.needsConfirmation(resolution))
    }

    @Test
    fun scenario_user_gives_ambiguous_response() {
        // 用户说"嗯..."（模糊）
        val resolution = resolver.parse(NicknameResolution(
            intent = "AMBIGUOUS",
            confidence = 0.5f
        ))
        assertFalse("AMBIGUOUS 不保存", resolver.decideSave(resolution).shouldSave)
        assertFalse("AMBIGUOUS 不清空", resolver.shouldClear(resolution))
        assertFalse("AMBIGUOUS 不需要确认", resolver.needsConfirmation(resolution))
    }
}
