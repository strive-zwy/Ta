package com.agent.ta.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agent.ta.data.default.DefaultAgent
import com.agent.ta.data.local.entity.AgentConfigEntity
import com.agent.ta.data.local.entity.FirstMeetingStateEntity
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.data.remote.dto.NicknameResolution
import com.agent.ta.di.ServiceLocator
import com.agent.ta.domain.firstmeeting.FirstMeetingPhase
import com.agent.ta.domain.firstmeeting.NicknameResolver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 首次见面 + 称呼提取集成测试（Task 15）
 *
 * 验证 ChatInteractor.processNicknameResolution 的完整流程：
 * 1. "叫我阿哲"只更新请求所属 Agent，状态完成
 * 2. "我叫张明，你叫我明哥"保存"明哥"；"我叫张明"不保存并追问
 * 3. 第一次模糊回答追问一次，第二次仍模糊后结束且不再追问
 * 4. "别叫我宝宝了，叫我阿哲"在首次见面结束后仍更新当前 Agent
 * 5. "直接叫你就行"清空当前 Agent 称呼
 *
 * 由于 ChatInteractor.processNicknameResolution 为 private 且依赖 LLM，
 * 本测试通过直接调用 NicknameResolver + FirstMeetingCoordinator + AgentConfigEditor
 * 复现该方法的处理逻辑，验证组件间集成正确性。
 *
 * 运行环境：emulator（需要 ServiceLocator + Room DB 初始化）
 */
@RunWith(AndroidJUnit4::class)
class FirstMeetingNicknameIntegrationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val agentConfigDao get() = ServiceLocator.agentConfigDao
    private val firstMeetingDao get() = ServiceLocator.firstMeetingStateDao
    private val coordinator get() = ServiceLocator.firstMeetingCoordinator
    private val editor get() = ServiceLocator.agentConfigEditor

    @Before
    fun setup() = runBlocking {
        val db = ServiceLocator.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM agent_config")
        db.execSQL("DELETE FROM first_meeting_state")
    }

    @After
    fun tearDown() = runBlocking {
        val db = ServiceLocator.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM agent_config")
        db.execSQL("DELETE FROM first_meeting_state")
    }

    /**
     * 插入一个 Agent 并初始化首次见面状态为 WAITING_NICKNAME（模拟问候已成功入库）
     */
    private suspend fun insertAgentAtWaitingNickname(name: String = "测试Agent"): Long {
        val config = DefaultAgent.create().copy(
            agent = DefaultAgent.create().agent.copy(name = name)
        )
        val configJson = json.encodeToString(AgentConfig.serializer(), config)
        val agentId = agentConfigDao.insert(
            AgentConfigEntity(
                configJson = configJson,
                agentName = name,
                importedAt = System.currentTimeMillis(),
                isActive = false
            )
        )
        firstMeetingDao.upsert(
            FirstMeetingStateEntity(
                agentId = agentId,
                phase = FirstMeetingPhase.WAITING_NICKNAME.id
            )
        )
        return agentId
    }

    /**
     * 读取指定 Agent 的 nicknameForUser
     */
    private suspend fun readNickname(agentId: Long): String {
        val entity = agentConfigDao.getById(agentId)!!
        val config = json.decodeFromString<AgentConfig>(entity.configJson)
        return config.agent.persona.nicknameForUser
    }

    /**
     * 复现 ChatInteractor.processNicknameResolution 的核心逻辑
     *
     * @param agentId 请求所属 Agent ID
     * @param rawResolution LLM 输出的 NicknameResolution
     * @param awaitingNickname 是否处于等待称呼阶段
     */
    private suspend fun processNicknameResolution(
        agentId: Long,
        rawResolution: NicknameResolution,
        awaitingNickname: Boolean
    ) {
        val resolution = NicknameResolver.parse(rawResolution)
        if (resolution.intent == "NONE") return

        if (awaitingNickname) {
            when (resolution.intent) {
                "EXPLICIT_NICKNAME", "CORRECTION" -> {
                    val decision = NicknameResolver.decideSave(resolution)
                    if (decision.shouldSave && decision.normalizedNickname != null) {
                        editor.updateAgent(agentId) { config ->
                            config.copy(
                                agent = config.agent.copy(
                                    persona = config.agent.persona.copy(
                                        nicknameForUser = decision.normalizedNickname
                                    )
                                )
                            )
                        }
                        coordinator.onNicknameCaptured(agentId, decision.normalizedNickname)
                    } else {
                        coordinator.onNicknameUnrecognized(agentId)
                    }
                }
                "CLEAR" -> coordinator.onNicknameUnrecognized(agentId)
                "DECLINED" -> coordinator.onUserDeclined(agentId)
                "SELF_INTRODUCTION", "AMBIGUOUS" -> coordinator.onNicknameUnrecognized(agentId)
            }
        } else {
            when (resolution.intent) {
                "EXPLICIT_NICKNAME", "CORRECTION" -> {
                    val decision = NicknameResolver.decideSave(resolution)
                    if (decision.shouldSave && decision.normalizedNickname != null) {
                        editor.updateAgent(agentId) { config ->
                            config.copy(
                                agent = config.agent.copy(
                                    persona = config.agent.persona.copy(
                                        nicknameForUser = decision.normalizedNickname
                                    )
                                )
                            )
                        }
                    }
                }
                "CLEAR" -> {
                    if (NicknameResolver.shouldClear(resolution)) {
                        editor.updateAgent(agentId) { config ->
                            config.copy(
                                agent = config.agent.copy(
                                    persona = config.agent.persona.copy(nicknameForUser = "")
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 测试 1："叫我阿哲"只更新请求所属 Agent，状态完成
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun explicit_nickname_updates_only_target_agent_and_completes() = runBlocking {
        val agentA = insertAgentAtWaitingNickname("AgentA")
        val agentB = insertAgentAtWaitingNickname("AgentB")

        // 模拟 LLM 对 agentA 的用户消息"叫我阿哲"的解析结果
        val resolution = NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "叫我阿哲",
            confidence = 0.95f,
            evidence = "叫我阿哲",
            shouldSave = true
        )
        processNicknameResolution(agentA, resolution, awaitingNickname = true)

        // agentA 称呼已保存为"阿哲"（"叫我"前缀被清洗）
        assertEquals("agentA 称呼应为阿哲", "阿哲", readNickname(agentA))

        // agentA 首次见面状态应为 COMPLETED_WITH_NICKNAME
        val fmA = coordinator.getPhase(agentA)
        assertEquals(
            "agentA 首次见面应完成",
            FirstMeetingPhase.COMPLETED_WITH_NICKNAME,
            fmA
        )

        // agentB 不受影响
        assertEquals("agentB 称呼应保持空", "", readNickname(agentB))
        val fmB = coordinator.getPhase(agentB)
        assertEquals(
            "agentB 首次见面应仍为 WAITING_NICKNAME",
            FirstMeetingPhase.WAITING_NICKNAME,
            fmB
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 测试 2："我叫张明，你叫我明哥"保存"明哥"；"我叫张明"不保存并追问
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun self_introduction_with_explicit_nickname_saves_explicit_part() = runBlocking {
        val agentId = insertAgentAtWaitingNickname()

        // LLM 正确识别"我叫张明，你叫我明哥"中的明确称呼"明哥"
        val resolution = NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "明哥",
            confidence = 0.92f,
            evidence = "你叫我明哥",
            shouldSave = true
        )
        processNicknameResolution(agentId, resolution, awaitingNickname = true)

        assertEquals("应保存明哥", "明哥", readNickname(agentId))
        assertEquals(
            "状态应完成",
            FirstMeetingPhase.COMPLETED_WITH_NICKNAME,
            coordinator.getPhase(agentId)
        )
    }

    @Test
    fun self_introduction_without_explicit_nickname_does_not_save_and_follows_up() = runBlocking {
        val agentId = insertAgentAtWaitingNickname()

        // LLM 识别"我叫张明"为 SELF_INTRODUCTION（只自我介绍，未明确要求称呼）
        val resolution = NicknameResolution(
            intent = "SELF_INTRODUCTION",
            nickname = "张明",
            confidence = 0.7f,
            evidence = "我叫张明",
            shouldSave = false
        )
        processNicknameResolution(agentId, resolution, awaitingNickname = true)

        assertEquals("不应保存称呼", "", readNickname(agentId))
        assertEquals(
            "应追问一次（FOLLOW_UP_ASKED）",
            FirstMeetingPhase.FOLLOW_UP_ASKED,
            coordinator.getPhase(agentId)
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 测试 3：第一次模糊回答追问一次，第二次仍模糊后结束且不再追问
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun first_ambiguous_reply_follows_up_second_ambiguous_completes_without_nickname() = runBlocking {
        val agentId = insertAgentAtWaitingNickname()

        // 第一次模糊回答（AMBIGUOUS）
        val firstResolution = NicknameResolution(
            intent = "AMBIGUOUS",
            nickname = null,
            confidence = 0.3f,
            evidence = "随便吧",
            shouldSave = false
        )
        processNicknameResolution(agentId, firstResolution, awaitingNickname = true)

        assertEquals("第一次后状态应为 FOLLOW_UP_ASKED", FirstMeetingPhase.FOLLOW_UP_ASKED, coordinator.getPhase(agentId))
        assertEquals("不应保存称呼", "", readNickname(agentId))

        // 第二次仍模糊
        val secondResolution = NicknameResolution(
            intent = "AMBIGUOUS",
            nickname = null,
            confidence = 0.2f,
            evidence = "都行",
            shouldSave = false
        )
        processNicknameResolution(agentId, secondResolution, awaitingNickname = true)

        assertEquals(
            "第二次后应结束（COMPLETED_WITHOUT_NICKNAME）",
            FirstMeetingPhase.COMPLETED_WITHOUT_NICKNAME,
            coordinator.getPhase(agentId)
        )
        assertEquals("仍不应保存称呼", "", readNickname(agentId))
        assertTrue("终态应标记为已完成", coordinator.getPhase(agentId)?.isCompleted == true)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 测试 4："别叫我宝宝了，叫我阿哲"在首次见面结束后仍更新当前 Agent
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun correction_after_meeting_completed_still_updates_agent() = runBlocking {
        val agentA = insertAgentAtWaitingNickname("AgentA")
        val agentB = insertAgentAtWaitingNickname("AgentB")

        // 先给 agentA 保存一个旧称呼"宝宝"并完成首次见面
        editor.updateAgent(agentA) { config ->
            config.copy(
                agent = config.agent.copy(
                    persona = config.agent.persona.copy(nicknameForUser = "宝宝")
                )
            )
        }
        coordinator.onNicknameCaptured(agentA, "宝宝")
        assertEquals(
            "前置：agentA 应已完成首次见面",
            FirstMeetingPhase.COMPLETED_WITH_NICKNAME,
            coordinator.getPhase(agentA)
        )

        // 模拟首次见面结束后用户说"别叫我宝宝了，叫我阿哲"
        // LLM 识别为 CORRECTION
        val correction = NicknameResolution(
            intent = "CORRECTION",
            nickname = "阿哲",
            confidence = 0.95f,
            evidence = "别叫我宝宝了，叫我阿哲",
            shouldSave = true
        )
        // awaitingNickname=false，因为首次见面已完成
        processNicknameResolution(agentA, correction, awaitingNickname = false)

        assertEquals("agentA 称呼应更新为阿哲", "阿哲", readNickname(agentA))
        // 状态不应变化（仍为 COMPLETED_WITH_NICKNAME）
        assertEquals(
            "agentA 状态应保持已完成",
            FirstMeetingPhase.COMPLETED_WITH_NICKNAME,
            coordinator.getPhase(agentA)
        )

        // agentB 不受影响
        assertEquals("agentB 称呼应保持空", "", readNickname(agentB))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 测试 5："直接叫你就行"清空当前 Agent 称呼
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun clear_intent_clears_nickname_for_target_agent_only() = runBlocking {
        val agentA = insertAgentAtWaitingNickname("AgentA")
        val agentB = insertAgentAtWaitingNickname("AgentB")

        // 先给两个 Agent 都保存称呼并完成首次见面
        editor.updateAgent(agentA) { config ->
            config.copy(agent = config.agent.copy(persona = config.agent.persona.copy(nicknameForUser = "阿哲")))
        }
        coordinator.onNicknameCaptured(agentA, "阿哲")

        editor.updateAgent(agentB) { config ->
            config.copy(agent = config.agent.copy(persona = config.agent.persona.copy(nicknameForUser = "小明")))
        }
        coordinator.onNicknameCaptured(agentB, "小明")

        // 模拟用户对 agentA 说"直接叫你就行"
        val clearResolution = NicknameResolution(
            intent = "CLEAR",
            nickname = "直接叫你就行",
            confidence = 0.98f,
            evidence = "直接叫你就行",
            shouldSave = false
        )
        processNicknameResolution(agentA, clearResolution, awaitingNickname = false)

        // agentA 称呼应被清空
        assertEquals("agentA 称呼应被清空", "", readNickname(agentA))

        // agentB 称呼不受影响
        assertEquals("agentB 称呼应保持小明", "小明", readNickname(agentB))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 补充测试：用户明确拒绝提供称呼，立即结束不追问
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun declined_intent_completes_without_nickname_immediately() = runBlocking {
        val agentId = insertAgentAtWaitingNickname()

        val declined = NicknameResolution(
            intent = "DECLINED",
            nickname = null,
            confidence = 0.9f,
            evidence = "不想说",
            shouldSave = false
        )
        processNicknameResolution(agentId, declined, awaitingNickname = true)

        assertEquals("不应保存称呼", "", readNickname(agentId))
        assertEquals(
            "应立即结束（COMPLETED_WITHOUT_NICKNAME）",
            FirstMeetingPhase.COMPLETED_WITHOUT_NICKNAME,
            coordinator.getPhase(agentId)
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 补充测试：低置信度的 EXPLICIT_NICKNAME 不保存并追问
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun low_confidence_explicit_nickname_treated_as_unrecognized() = runBlocking {
        val agentId = insertAgentAtWaitingNickname()

        // confidence 低于阈值 0.85
        val lowConfidence = NicknameResolution(
            intent = "EXPLICIT_NICKNAME",
            nickname = "阿哲",
            confidence = 0.5f,
            evidence = "可能是阿哲？",
            shouldSave = true
        )
        processNicknameResolution(agentId, lowConfidence, awaitingNickname = true)

        assertEquals("低置信度不应保存称呼", "", readNickname(agentId))
        assertEquals(
            "应追问一次",
            FirstMeetingPhase.FOLLOW_UP_ASKED,
            coordinator.getPhase(agentId)
        )
    }
}
