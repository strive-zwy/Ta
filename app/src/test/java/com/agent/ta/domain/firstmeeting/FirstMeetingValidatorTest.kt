package com.agent.ta.domain.firstmeeting

import com.agent.ta.data.remote.dto.AgentReply
import com.agent.ta.data.remote.dto.FirstMeetingMeta
import com.agent.ta.data.remote.dto.ReplyItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FirstMeetingValidator 单元测试（Task 12）
 *
 * 验证：
 * 1. isMetaValid 在两个目标都达成时返回 true
 * 2. isMetaValid 在缺少任一目标时返回 false
 * 3. isMetaValid 在 meta 为 null 时按 requireBothGoals 决定
 * 4. buildCorrectionHint 包含缺失目标的提示
 * 5. buildFallbackReply 返回合格的 AgentReply（自我介绍 + 询问称呼）
 */
class FirstMeetingValidatorTest {

    // ===== isMetaValid =====

    @Test
    fun isMetaValid_both_goals_true_returns_true() {
        val reply = AgentReply(
            replies = listOf(ReplyItem(replyText = "嗨～我是小雅")),
            firstMeetingMeta = FirstMeetingMeta(introducedSelf = true, askedForNickname = true)
        )
        assertTrue("两个目标都达成应合格", FirstMeetingValidator.isMetaValid(reply, requireBothGoals = true))
    }

    @Test
    fun isMetaValid_missing_introducedSelf_returns_false() {
        val reply = AgentReply(
            replies = listOf(ReplyItem(replyText = "你叫什么名字呀？")),
            firstMeetingMeta = FirstMeetingMeta(introducedSelf = false, askedForNickname = true)
        )
        assertFalse("缺少自我介绍应不合格", FirstMeetingValidator.isMetaValid(reply, requireBothGoals = true))
    }

    @Test
    fun isMetaValid_missing_askedForNickname_returns_false() {
        val reply = AgentReply(
            replies = listOf(ReplyItem(replyText = "嗨～我是小雅")),
            firstMeetingMeta = FirstMeetingMeta(introducedSelf = true, askedForNickname = false)
        )
        assertFalse("缺少询问称呼应不合格", FirstMeetingValidator.isMetaValid(reply, requireBothGoals = true))
    }

    @Test
    fun isMetaValid_null_meta_with_requireBothGoals_returns_false() {
        val reply = AgentReply(replies = listOf(ReplyItem(replyText = "嗨")))
        assertFalse(
            "meta 为 null 且 requireBothGoals=true 时应不合格",
            FirstMeetingValidator.isMetaValid(reply, requireBothGoals = true)
        )
    }

    @Test
    fun isMetaValid_null_meta_without_requireBothGoals_returns_true() {
        val reply = AgentReply(replies = listOf(ReplyItem(replyText = "嗨")))
        assertTrue(
            "meta 为 null 且 requireBothGoals=false 时应合格（REPLY 场景不强制）",
            FirstMeetingValidator.isMetaValid(reply, requireBothGoals = false)
        )
    }

    @Test
    fun isMetaValid_partial_meta_without_requireBothGoals_returns_true() {
        val reply = AgentReply(
            replies = listOf(ReplyItem(replyText = "嗨")),
            firstMeetingMeta = FirstMeetingMeta(introducedSelf = false, askedForNickname = false)
        )
        assertTrue(
            "REPLY 场景不强制两个目标，应合格",
            FirstMeetingValidator.isMetaValid(reply, requireBothGoals = false)
        )
    }

    // ===== buildCorrectionHint =====

    @Test
    fun buildCorrectionHint_null_meta_lists_both_goals() {
        val hint = FirstMeetingValidator.buildCorrectionHint(null)
        assertTrue("应提到自我介绍", hint.contains("自我介绍"))
        assertTrue("应提到询问用户称呼", hint.contains("询问用户称呼"))
        assertTrue("应要求 introducedSelf=true", hint.contains("introducedSelf=true"))
        assertTrue("应要求 askedForNickname=true", hint.contains("askedForNickname=true"))
    }

    @Test
    fun buildCorrectionHint_missing_introducedSelf_lists_only_that_goal() {
        val meta = FirstMeetingMeta(introducedSelf = false, askedForNickname = true)
        val hint = FirstMeetingValidator.buildCorrectionHint(meta)
        assertTrue("应提到自我介绍", hint.contains("自我介绍"))
        assertFalse("不应提到询问称呼（已达成）", hint.contains("询问用户称呼"))
    }

    @Test
    fun buildCorrectionHint_missing_askedForNickname_lists_only_that_goal() {
        val meta = FirstMeetingMeta(introducedSelf = true, askedForNickname = false)
        val hint = FirstMeetingValidator.buildCorrectionHint(meta)
        assertTrue("应提到询问用户称呼", hint.contains("询问用户称呼"))
        assertFalse("不应提到自我介绍（已达成）", hint.contains("自我介绍"))
    }

    // ===== buildFallbackReply =====

    @Test
    fun buildFallbackReply_contains_self_introduction_with_agent_name() {
        val reply = FirstMeetingValidator.buildFallbackReply("小雅")
        val combinedText = reply.replies.joinToString(" ") { it.replyText }
        assertTrue("兜底回复应包含 Agent 名字", combinedText.contains("小雅"))
    }

    @Test
    fun buildFallbackReply_contains_nickname_question() {
        val reply = FirstMeetingValidator.buildFallbackReply("小雅")
        val combinedText = reply.replies.joinToString(" ") { it.replyText }
        assertTrue(
            "兜底回复应包含询问称呼",
            combinedText.contains("名字") || combinedText.contains("叫什么")
        )
    }

    @Test
    fun buildFallbackReply_meta_marks_both_goals_true() {
        val reply = FirstMeetingValidator.buildFallbackReply("小雅")
        val meta = reply.firstMeetingMeta
        assertTrue("兜底回复 meta 不应为 null", meta != null)
        assertTrue("兜底回复 introducedSelf 应为 true", meta!!.introducedSelf)
        assertTrue("兜底回复 askedForNickname 应为 true", meta.askedForNickname)
    }

    @Test
    fun buildFallbackReply_has_at_least_two_replies() {
        val reply = FirstMeetingValidator.buildFallbackReply("小雅")
        assertTrue(
            "兜底回复应至少 2 条消息（自我介绍 + 询问称呼）",
            reply.replies.size >= 2
        )
    }

    @Test
    fun buildFallbackReply_passes_validation() {
        val reply = FirstMeetingValidator.buildFallbackReply("小雅")
        assertTrue(
            "兜底回复应通过 isMetaValid 校验",
            FirstMeetingValidator.isMetaValid(reply, requireBothGoals = true)
        )
    }
}
