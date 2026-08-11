package com.agent.ta.domain

import com.agent.ta.data.default.DefaultAgent
import com.agent.ta.data.model.AgentState
import com.agent.ta.domain.anchor.ActivityAnchor
import com.agent.ta.domain.anchor.AnchorSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 首次见面 Prompt 单元测试（Task 12）
 *
 * 验证 PromptBuilder 在 FIRST_MEETING_GREETING / FIRST_MEETING_REPLY 场景下：
 * 1. 包含"首次见面"场景标识
 * 2. 包含"主动发起"语义（GREETING 场景）
 * 3. 不得引用历史（含"不得引用任何对话历史"约束）
 * 4. 必须自我介绍（含 Agent 名字 + 自我介绍要求）
 * 5. 必须询问称呼（含"询问称呼"要求）
 * 6. 要求 2-3 条短消息
 * 7. 要求输出 firstMeetingMeta 元数据
 * 8. NORMAL 场景不注入首次见面引导（隔离性）
 */
class FirstMeetingPromptTest {

    private lateinit var promptBuilder: PromptBuilder
    private lateinit var config: com.agent.ta.data.model.AgentConfig

    @Before
    fun setup() {
        promptBuilder = PromptBuilder()
        config = DefaultAgent.create()
    }

    private fun buildPrompt(scene: ConversationScene): String {
        val messages = promptBuilder.build(
            config = config,
            state = AgentState.NORMAL,
            userNickname = "",
            memories = emptyList(),
            recentMessages = emptyList(),
            scene = scene
        )
        // 返回 system prompt（第一条消息）
        return messages.firstOrNull { it.role == "system" }?.content ?: ""
    }

    // ===== FIRST_MEETING_GREETING 场景 =====

    @Test
    fun greeting_scene_contains_first_meeting_marker() {
        val prompt = buildPrompt(ConversationScene.FIRST_MEETING_GREETING)
        assertTrue("GREETING 场景应包含「首次见面」标识", prompt.contains("首次见面"))
    }

    @Test
    fun greeting_scene_contains_active_initiation_semantics() {
        val prompt = buildPrompt(ConversationScene.FIRST_MEETING_GREETING)
        assertTrue(
            "GREETING 场景应表明 Agent 主动发起",
            prompt.contains("主动发起") || prompt.contains("你主动发起")
        )
    }

    @Test
    fun greeting_scene_forbids_referencing_history() {
        val prompt = buildPrompt(ConversationScene.FIRST_MEETING_GREETING)
        assertTrue(
            "GREETING 场景应明确禁止引用对话历史",
            prompt.contains("不得引用") && prompt.contains("历史")
        )
    }

    @Test
    fun greeting_scene_requires_self_introduction_with_agent_name() {
        val prompt = buildPrompt(ConversationScene.FIRST_MEETING_GREETING)
        val agentName = config.agent.name
        assertTrue(
            "GREETING 场景应要求自我介绍并包含 Agent 名字「$agentName」",
            prompt.contains("自我介绍") && prompt.contains(agentName)
        )
    }

    @Test
    fun greeting_scene_requires_asking_nickname() {
        val prompt = buildPrompt(ConversationScene.FIRST_MEETING_GREETING)
        assertTrue(
            "GREETING 场景应要求询问用户称呼",
            prompt.contains("询问称呼") || prompt.contains("问用户")
        )
    }

    @Test
    fun greeting_scene_requires_2_to_3_short_messages() {
        val prompt = buildPrompt(ConversationScene.FIRST_MEETING_GREETING)
        assertTrue(
            "GREETING 场景应要求 2-3 条短消息连发",
            prompt.contains("2-3") && prompt.contains("短消息")
        )
    }

    @Test
    fun greeting_scene_requires_first_meeting_meta_output() {
        val prompt = buildPrompt(ConversationScene.FIRST_MEETING_GREETING)
        assertTrue(
            "GREETING 场景应要求输出 firstMeetingMeta 元数据",
            prompt.contains("firstMeetingMeta") &&
                prompt.contains("introducedSelf") &&
                prompt.contains("askedForNickname")
        )
    }

    @Test
    fun greeting_scene_forbids_pretending_to_know_user() {
        val prompt = buildPrompt(ConversationScene.FIRST_MEETING_GREETING)
        assertTrue(
            "GREETING 场景应禁止假装认识用户",
            prompt.contains("不得假装认识") || prompt.contains("不得提到之前的对话")
        )
    }

    @Test
    fun continuous_chat_uses_activity_as_fact_without_requiring_repetition() {
        val prompt = promptBuilder.build(
            config = config,
            state = AgentState.NORMAL,
            userNickname = "",
            memories = emptyList(),
            recentMessages = emptyList(),
            activityAnchor = ActivityAnchor(
                activity = "休息",
                state = AgentState.NORMAL,
                startedAt = System.currentTimeMillis() - 10_000L,
                expectedEnd = System.currentTimeMillis() + 60_000L,
                slotStart = "12:00",
                slotEnd = "13:00",
                source = AnchorSource.SCHEDULE,
                replyable = true
            ),
            continuousRound = 2
        ).first { it.role == "system" }.content

        assertTrue(prompt.contains("不是每轮必须提起的话题"))
        assertTrue(prompt.contains("不要再次复述"))
        assertFalse(prompt.contains("本次回复所有内容必须围绕「休息」"))
    }

    // ===== FIRST_MEETING_REPLY 场景 =====

    @Test
    fun reply_scene_contains_first_meeting_marker() {
        val prompt = buildPrompt(ConversationScene.FIRST_MEETING_REPLY)
        assertTrue("REPLY 场景应包含「首次见面」标识", prompt.contains("首次见面"))
    }

    @Test
    fun reply_scene_requires_self_introduction() {
        val prompt = buildPrompt(ConversationScene.FIRST_MEETING_REPLY)
        val agentName = config.agent.name
        assertTrue(
            "REPLY 场景应要求自我介绍并包含 Agent 名字",
            prompt.contains("自我介绍") && prompt.contains(agentName)
        )
    }

    @Test
    fun reply_scene_requires_asking_nickname() {
        val prompt = buildPrompt(ConversationScene.FIRST_MEETING_REPLY)
        assertTrue(
            "REPLY 场景应要求询问用户称呼",
            prompt.contains("询问称呼") || prompt.contains("问用户")
        )
    }

    @Test
    fun reply_scene_forbids_abrupt_proactive_greeting() {
        val prompt = buildPrompt(ConversationScene.FIRST_MEETING_REPLY)
        assertTrue(
            "REPLY 场景应禁止补发突兀的主动问候",
            prompt.contains("不得补发") || prompt.contains("突兀")
        )
    }

    @Test
    fun reply_scene_requires_first_meeting_meta_output() {
        val prompt = buildPrompt(ConversationScene.FIRST_MEETING_REPLY)
        assertTrue(
            "REPLY 场景应要求输出 firstMeetingMeta 元数据",
            prompt.contains("firstMeetingMeta")
        )
    }

    // ===== NORMAL 场景隔离性 =====

    @Test
    fun normal_scene_does_not_inject_first_meeting_guidance() {
        val prompt = buildPrompt(ConversationScene.NORMAL)
        assertFalse(
            "NORMAL 场景不应注入首次见面专用引导",
            prompt.contains("首次见面·主动问候") || prompt.contains("首次见面·用户先发消息")
        )
        assertFalse(
            "NORMAL 场景不应要求 firstMeetingMeta 元数据",
            prompt.contains("firstMeetingMeta")
        )
    }

    // ===== FirstMeetingMeta 数据类 =====

    @Test
    fun first_meeting_meta_defaults_are_false() {
        val meta = com.agent.ta.data.remote.dto.FirstMeetingMeta()
        assertFalse("默认 introducedSelf 应为 false", meta.introducedSelf)
        assertFalse("默认 askedForNickname 应为 false", meta.askedForNickname)
    }

    @Test
    fun first_meeting_meta_can_be_constructed_with_true_values() {
        val meta = com.agent.ta.data.remote.dto.FirstMeetingMeta(
            introducedSelf = true,
            askedForNickname = true
        )
        assertTrue(meta.introducedSelf)
        assertTrue(meta.askedForNickname)
    }

    // ===== 场景判断辅助 =====

    @Test
    fun first_meeting_scenes_are_marked_as_first_meeting() {
        assertTrue(ConversationScene.FIRST_MEETING_GREETING.isFirstMeeting)
        assertTrue(ConversationScene.FIRST_MEETING_REPLY.isFirstMeeting)
        assertFalse(ConversationScene.NORMAL.isFirstMeeting)
    }
}
