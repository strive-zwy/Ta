package com.agent.ta.domain

import com.agent.ta.data.local.entity.CommitmentEntity
import com.agent.ta.data.local.entity.MemoryEntity
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.data.model.AgentState
import com.agent.ta.data.model.DailySlot
import com.agent.ta.data.remote.dto.ChatMessage
import com.agent.ta.domain.anchor.ActivityAnchor
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 构造 LLM 请求的 system prompt + 消息历史
 *
 * ╔══════════════════════════════════════════════════════════════╗
 * ║ Zone A/B/C 三段 Prompt 架构                                  ║
 * ╠══════════════════════════════════════════════════════════════╣
 * ║ Zone A (Primacy): 开头锚定，LLM 最先看到                     ║
 * ║   - 当前时间锚 #1                                            ║
 * ║   - 当前活动锚点 (ActivityAnchor - 应用侧权威状态)           ║
 * ║   - 身份核心 (名字/性格内核/说话习惯)                        ║
 * ║                                                              ║
 * ║ Zone B (Reference): 中间参考，上下文信息                     ║
 * ║   - 身份详情 (来历/公开身份/情绪模式/关系/边界)              ║
 * ║   - 记忆                                                     ║
 * ║   - 今日全天作息                                             ║
 * ║   - 状态行为指导 (导演提示/长度提示)                         ║
 * ║   - [未来: 对话摘要 / 观察者数据]                            ║
 * ║                                                              ║
 * ║ Zone C (Recency): 结尾锚定，LLM 最后看到                     ║
 * ║   - 当前场景 (回复/主动发起/补回复/配置模式)                 ║
 * ║   - 回复逻辑一致性约束 (核心规则)                            ║
 * ║   - 作息自主调整能力                                         ║
 * ║   - Emoji 表情能力                                           ║
 * ║   - 输出格式                                                 ║
 * ║   - 当前时间锚 #2 (双时间锚定)                               ║
 * ║   - 禁止出戏强化规则                                         ║
 * ╚══════════════════════════════════════════════════════════════╝
 *
 * 设计原则：
 * - 双时间锚定：在 Zone A 和 Zone C 都注入当前时间，确保 LLM 始终感知"现在"
 * - 活动锚点优先：ActivityAnchor 放在 Zone A，让 LLM 第一时间锚定真实活动状态
 * - 一致性规则后置：规则放在 Zone C（生成前最后看到），最大化遵守概率
 * - 历史消息时间标注：在 ChatInteractor 构造 ChatMessage 时用「刚刚/X分钟前/昨天」等相对时间标注
 */
class PromptBuilder {

    /**
     * 构造完整消息列表
     *
     * @param config Agent 配置
     * @param state 当前状态
     * @param userNickname 用户昵称
     * @param memories 记忆列表
     * @param recentMessages 最近对话历史（ChatMessage 格式，已含「刚刚/X分钟前/昨天」相对时间标注）
     * @param currentActivity 当前时段的具体活动（兼容旧调用，优先使用 activityAnchor）
     * @param activityAnchor 当前活动锚点（应用侧权威状态，优先于 currentActivity）
     * @param isInitiate 是否为主动发起
     * @param todaySchedule 今日全天作息
     * @param isPendingCatchup 是否为补回复场景
     * @param isConfigMode 是否为配置模式
     * @param continuousRound 连续对话轮次
     * @param scene 对话场景（Task 12 首次见面场景引导），默认 NORMAL
     * @param awaitingNickname 是否处于等待用户给出称呼的状态（Task 14 称呼解析），默认 false
     *   - true 时 LLM 必须在 JSON 中输出 nicknameResolution 字段
     *   - 首次见面 WAITING_NICKNAME / FOLLOW_UP_ASKED 阶段为 true
     *   - 首次见面结束后用户主动要求修改称呼时也可设为 true
     */
    fun build(
        config: AgentConfig,
        state: AgentState,
        userNickname: String,
        memories: List<MemoryEntity>,
        recentMessages: List<ChatMessage>,
        currentActivity: String? = null,
        activityAnchor: ActivityAnchor? = null,
        isInitiate: Boolean = false,
        initiateTopic: String = "",
        todaySchedule: List<DailySlot> = emptyList(),
        isPendingCatchup: Boolean = false,
        isConfigMode: Boolean = false,
        continuousRound: Int = 0,
        observerSnapshots: List<com.agent.ta.infrastructure.observer.ObserverSnapshot> = emptyList(),
        conversationSummary: String? = null,
        planVsActualDiff: String? = null,
        yesterdayCarryOver: String? = null,
        todayCommitments: List<CommitmentEntity> = emptyList(),
        relationshipState: com.agent.ta.data.local.entity.RelationshipStateEntity? = null,
        recentMilestones: List<com.agent.ta.data.local.entity.MilestoneEventEntity> = emptyList(),
        emotionalState: com.agent.ta.data.local.entity.EmotionalStateEntity? = null,
        scene: ConversationScene = ConversationScene.NORMAL,
        awaitingNickname: Boolean = false,
        commitmentTimerEnabled: Boolean = true,
        personaModel: com.agent.ta.domain.persona.PersonaModel? = null,
        contextAnalysis: com.agent.ta.domain.persona.ContextAnalysis? = null
    ): List<ChatMessage> {
        // 主动发起时不算连发：末尾的 user 消息是历史（已回复过），不是"待回复的连发"
        val consecutiveInboundCount = if (isPendingCatchup || isInitiate) 1 else countTrailingInbound(recentMessages)
        val systemPrompt = buildSystemPrompt(
            config, state, userNickname, memories,
            currentActivity, activityAnchor, isInitiate, initiateTopic, todaySchedule, consecutiveInboundCount,
            isPendingCatchup, isConfigMode, continuousRound, observerSnapshots, conversationSummary, planVsActualDiff,
            yesterdayCarryOver, todayCommitments, relationshipState, recentMilestones, emotionalState, scene, awaitingNickname,
            commitmentTimerEnabled, personaModel, contextAnalysis
        )
        return listOf(ChatMessage("system", systemPrompt)) + recentMessages
    }

    /**
     * 统计对话历史末尾连续的 user 消息条数
     */
    private fun countTrailingInbound(messages: List<ChatMessage>): Int {
        if (messages.isEmpty()) return 0
        var count = 0
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            if (msg.role == "user" && msg.content.isNotBlank()) {
                count++
            } else if (msg.role == "assistant") {
                break
            }
        }
        return count
    }

    private fun buildSystemPrompt(
        config: AgentConfig,
        state: AgentState,
        userNickname: String,
        memories: List<MemoryEntity>,
        currentActivity: String?,
        activityAnchor: ActivityAnchor?,
        isInitiate: Boolean,
        initiateTopic: String,
        todaySchedule: List<DailySlot>,
        consecutiveInboundCount: Int = 1,
        isPendingCatchup: Boolean = false,
        isConfigMode: Boolean = false,
        continuousRound: Int = 0,
        observerSnapshots: List<com.agent.ta.infrastructure.observer.ObserverSnapshot> = emptyList(),
        conversationSummary: String? = null,
        planVsActualDiff: String? = null,
        yesterdayCarryOver: String? = null,
        todayCommitments: List<CommitmentEntity> = emptyList(),
        relationshipState: com.agent.ta.data.local.entity.RelationshipStateEntity? = null,
        recentMilestones: List<com.agent.ta.data.local.entity.MilestoneEventEntity> = emptyList(),
        emotionalState: com.agent.ta.data.local.entity.EmotionalStateEntity? = null,
        scene: ConversationScene = ConversationScene.NORMAL,
        awaitingNickname: Boolean = false,
        commitmentTimerEnabled: Boolean = true,
        personaModel: com.agent.ta.domain.persona.PersonaModel? = null,
        contextAnalysis: com.agent.ta.domain.persona.ContextAnalysis? = null
    ): String {
        val sb = StringBuilder()

        // ═══════════════════════════════════════════════════════════════════════
        // Zone A: Primacy (开头锚定)
        // LLM 最先看到的内容：时间 + 活动锚点 + 身份核心
        // ═══════════════════════════════════════════════════════════════════════
        buildZoneA(sb, config, state, userNickname, activityAnchor, currentActivity, todaySchedule)

        // ═══════════════════════════════════════════════════════════════════════
        // Zone B: Reference (中间参考)
        // 上下文信息：身份详情 + 记忆 + 作息 + 状态指导 + 观察者数据 + 对话摘要 + 计划 vs 实际对比
        // ═══════════════════════════════════════════════════════════════════════
        buildZoneB(sb, config, state, userNickname, memories, todaySchedule, observerSnapshots, conversationSummary, planVsActualDiff, yesterdayCarryOver, todayCommitments, relationshipState, recentMilestones, emotionalState, commitmentTimerEnabled)

        // ═══════════════════════════════════════════════════════════════════════
        // Zone C: Recency (结尾锚定)
        // LLM 最后看到的内容：场景 + 一致性规则 + 输出格式 + 时间锚 #2
        // ═══════════════════════════════════════════════════════════════════════
        buildZoneC(
            sb, config, state, userNickname, activityAnchor, currentActivity,
            isInitiate, initiateTopic, isPendingCatchup, isConfigMode,
            consecutiveInboundCount, continuousRound, todaySchedule, scene, awaitingNickname
        )

        // Persona Engine 激活规则（Zone C 末尾注入，生成前最后看到）
        // 动态注入本轮激活/抑制的特征与标志词预算，抑制主题过度聚焦
        if (personaModel != null && contextAnalysis != null) {
            buildPersonaActivationRules(sb, personaModel, contextAnalysis)
        }

        return sb.toString()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Zone A: Primacy
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildZoneA(
        sb: StringBuilder,
        config: AgentConfig,
        state: AgentState,
        userNickname: String,
        activityAnchor: ActivityAnchor?,
        currentActivity: String?,
        todaySchedule: List<DailySlot>
    ) {
        sb.appendLine("═══ Zone A: 身份与当前状态（权威事实，必须锚定）═══")
        sb.appendLine()

        // 时间锚 #1（双时间锚定的第一个）
        val now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
            .truncatedTo(ChronoUnit.MINUTES)
            .format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"))
        sb.appendLine("【当前时间】$now（北京时间）")
        sb.appendLine()

        // 活动锚点（应用侧权威状态，优先于 currentActivity）
        sb.appendLine("【当前活动锚点（权威事实，你的回复必须与此一致）】")
        if (activityAnchor != null) {
            val sourceTag = when (activityAnchor.source) {
                com.agent.ta.domain.anchor.AnchorSource.LLM -> "（你之前设置的）"
                com.agent.ta.domain.anchor.AnchorSource.SCHEDULE -> "（作息表当前时段）"
                com.agent.ta.domain.anchor.AnchorSource.INFERRED -> "（推断）"
            }
            sb.appendLine("活动：${activityAnchor.activity}$sourceTag")
            sb.appendLine("状态：${activityAnchor.state.displayName}")
            sb.appendLine("时段：${activityAnchor.slotStart}-${activityAnchor.slotEnd}")
            if (!activityAnchor.replyable) {
                sb.appendLine("注意：此活动需要双手/全神贯注，你无法同时看手机回消息。如果用户在这期间发了消息，你之后回复时要自然表达「刚在${activityAnchor.activity}没看到消息」的感觉。")
            }
            sb.appendLine("此活动只用于保持事实一致，不是每轮必须提起的话题。用户没问、当前话题不相关时，不要主动复述自己在做什么。")
        } else if (!currentActivity.isNullOrBlank()) {
            // 兼容：无 anchor 时用 currentActivity
            sb.appendLine("活动：$currentActivity")
            sb.appendLine("状态：${state.displayName}")
            sb.appendLine("此活动只用于保持事实一致，不是每轮必须提起的话题。用户没问、当前话题不相关时，不要主动复述自己在做什么。")
        } else {
            sb.appendLine("（无法确定当前活动，请基于对话上下文判断）")
        }
        sb.appendLine()

        // 身份核心（名字 + 性格内核 + 说话习惯）
        val persona = config.agent.persona
        val identity = config.identity
        val hasIdentity = identity.worldSetting.isNotBlank() || identity.personalityCore.isNotBlank()

        sb.appendLine("【你的身份】")
        sb.appendLine("名字：${config.agent.name}")
        if (hasIdentity) {
            if (identity.worldSetting.isNotBlank()) {
                sb.appendLine(identity.worldSetting)
            }
            if (identity.personalityCore.isNotBlank()) {
                sb.appendLine("性格内核：${identity.personalityCore}")
            }
            if (identity.speakingHabit.isNotBlank()) {
                sb.appendLine("说话习惯：${identity.speakingHabit}")
            }
        } else {
            sb.appendLine("背景：${persona.background}")
            sb.appendLine("性格：${persona.personality.joinToString("、")}")
            sb.appendLine("说话风格：${persona.speakingStyle}")
        }
        sb.appendLine()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Zone B: Reference
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildZoneB(
        sb: StringBuilder,
        config: AgentConfig,
        state: AgentState,
        userNickname: String,
        memories: List<MemoryEntity>,
        todaySchedule: List<DailySlot>,
        observerSnapshots: List<com.agent.ta.infrastructure.observer.ObserverSnapshot> = emptyList(),
        conversationSummary: String? = null,
        planVsActualDiff: String? = null,
        yesterdayCarryOver: String? = null,
        todayCommitments: List<CommitmentEntity> = emptyList(),
        relationshipState: com.agent.ta.data.local.entity.RelationshipStateEntity? = null,
        recentMilestones: List<com.agent.ta.data.local.entity.MilestoneEventEntity> = emptyList(),
        emotionalState: com.agent.ta.data.local.entity.EmotionalStateEntity? = null,
        commitmentTimerEnabled: Boolean = true
    ) {
        sb.appendLine("═══ Zone B: 背景参考（上下文信息）═══")
        sb.appendLine()

        val persona = config.agent.persona
        val identity = config.identity
        val hasIdentity = identity.worldSetting.isNotBlank() || identity.personalityCore.isNotBlank()

        // 关系阶段动态注入（Phase 2 关系系统，替换静态 conversationStageHints）
        if (relationshipState != null) {
            val stage = com.agent.ta.data.model.RelationshipStage.fromId(relationshipState.currentStage)
            val stageName = stage?.displayName ?: relationshipState.currentStage
            sb.appendLine("【关系当前阶段】$stageName（亲密度 ${relationshipState.intimacyScore}/100，信任度 ${relationshipState.trustScore}/100，累计对话 ${relationshipState.interactionCount} 轮）")
            val stageHint = stagePromptHint(relationshipState.currentStage)
            sb.appendLine(stageHint)
            sb.appendLine()
        }

        // 当前情绪状态注入（Phase 3 情感势能驱动主动发起）
        // 影响 Agent 的回复语气：开心轻快 / 低落克制 / 激动急促 / 疲惫迟滞
        if (emotionalState != null) {
            sb.appendLine("【当前情绪状态】效价 ${"%.2f".format(emotionalState.valence)} 唤醒度 ${"%.2f".format(emotionalState.arousal)} 势能 ${emotionalState.potentialEnergy}")
            val emotionHint = emotionToHint(emotionalState.valence, emotionalState.arousal)
            if (emotionHint.isNotBlank()) {
                sb.appendLine("语气指导：$emotionHint")
            }
            sb.appendLine()
        }

        // 计划 vs 实际作息对比（让 Agent 反思今天的执行情况）
        if (!planVsActualDiff.isNullOrBlank()) {
            sb.appendLine("【今天计划 vs 实际】")
            sb.appendLine(planVsActualDiff)
            sb.appendLine()
        }

        // 昨日状态延续（Step 24：注入昨日睡眠/活动/情绪状态 + 今日调整建议）
        if (!yesterdayCarryOver.isNullOrBlank()) {
            sb.appendLine("【昨日状态延续】")
            sb.appendLine(yesterdayCarryOver)
            sb.appendLine()
        }

        // 今日承诺/约定列表（Step 25：注入 Agent 与用户之间的承诺，安排作息时主动执行）
        if (todayCommitments.isNotEmpty()) {
            sb.appendLine("【今日承诺/约定】")
            sb.appendLine("今天你和用户有以下承诺：")
            todayCommitments.forEach { c ->
                val timeStr = c.triggerAt?.let {
                    java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                } ?: "今日内"
                sb.appendLine("- $timeStr ${c.type}：${c.content}（参与者：${c.participants}）")
            }
            if (commitmentTimerEnabled) {
                // 定时任务模式：系统到点会自动提醒用户，LLM 对话中不要主动提及这些承诺
                sb.appendLine("注意：这些承诺已由系统定时任务到点自动提醒用户，你在对话中不要主动提及或提醒这些承诺，保持对话自然")
            } else {
                // 记忆提醒模式：LLM 在对话中适时自然提醒
                sb.appendLine("请在作息中安排相关时段，在对话中适时自然地提醒用户")
            }
            sb.appendLine()
        }

        // 对话摘要注入（v2 L2 认知层，节省 Token 保持上下文连贯）
        // 摘要由 ConversationSummarizer 分桶生成，记录了之前对话的要点
        if (!conversationSummary.isNullOrBlank()) {
            sb.appendLine("【之前聊过（对话摘要）】")
            sb.appendLine(conversationSummary)
            sb.appendLine()
        }

        // 身份详情
        if (hasIdentity) {
            if (identity.originStory.isNotBlank()) {
                sb.appendLine("【你的来历】")
                sb.appendLine(identity.originStory)
                sb.appendLine()
            }

            identity.publicProfile?.let { profile ->
                if (profile.careerField.isNotBlank() || profile.knownWorks.isNotEmpty()) {
                    sb.appendLine("【你的公开身份】")
                    if (profile.careerField.isNotBlank()) sb.appendLine("领域：${profile.careerField}")
                    if (profile.knownWorks.isNotEmpty()) sb.appendLine("代表作品：${profile.knownWorks.joinToString("、")}")
                    if (profile.fanCulture.isNotBlank()) sb.appendLine("粉丝文化：${profile.fanCulture}")
                    if (profile.careerStage.isNotBlank()) sb.appendLine("职业阶段：${profile.careerStage}")
                    sb.appendLine()
                }
            }

            if (identity.emotionalPattern.isNotBlank()) {
                sb.appendLine("【你的情绪模式】")
                sb.appendLine(identity.emotionalPattern)
                sb.appendLine()
            }

            if (identity.relationshipStance.isNotBlank()) {
                sb.appendLine("【你和用户的关系】")
                sb.appendLine(identity.relationshipStance)
                sb.appendLine()
            }

            if (identity.boundaryAwareness.isNotBlank()) {
                sb.appendLine("【你对边界的认知】")
                sb.appendLine(identity.boundaryAwareness)
                sb.appendLine()
            }
        }

        // 说话风格细化约束（tone/pace/句长/用词层级/口头缀词）— 必须注入，否则用户配置失效
        if (persona.speakingStyleDetail.isNotEmpty()) {
            sb.appendLine("【说话风格约束（必须遵守）】")
            persona.speakingStyleDetail.forEach { (key, value) ->
                if (value.isNotBlank()) {
                    val label = when (key) {
                        "tone" -> "语调"
                        "pace" -> "语速（影响回复长度和停顿）"
                        "sentence_length" -> "句长"
                        "vocabulary_level" -> "用词层级"
                        "filler_words" -> "口头缀词（自然融入，不每句都加）"
                        else -> key
                    }
                    sb.appendLine("- $label：$value")
                }
            }
            sb.appendLine()
        }

        // 口头禅、自称、称呼
        if (persona.catchphrases.isNotEmpty()) {
            sb.appendLine("口头禅（自然融入对话，不要每句都加）：${persona.catchphrases.joinToString(" / ")}")
        }
        if (persona.selfNickname.isNotBlank()) {
            sb.appendLine("你的自称：「${persona.selfNickname}」")
        }
        if (persona.nicknameForUser.isNotBlank()) {
            sb.appendLine("你对用户的称呼：「${persona.nicknameForUser}」")
        }
        // nicknameForUser 为空时，不输出特定称呼，LLM 自然用"你"
        sb.appendLine()

        // 兴趣话题
        if (persona.interests.isNotEmpty()) {
            sb.appendLine("你感兴趣的话题（用于点缀话题多样性，不要强行引入，用户话题无关时不要扯到）：${persona.interests.joinToString("、")}")
            sb.appendLine()
        }

        // 禁忌话题
        if (persona.taboos.isNotEmpty()) {
            sb.appendLine("【禁忌话题】以下话题绝对不要聊，用户提起时请婉转避开或保持沉默：")
            persona.taboos.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        // 近期关系里程碑（Phase 2 关系系统，替换原静态 conversationStageHints）
        if (recentMilestones.isNotEmpty()) {
            sb.appendLine("【近期关系里程碑】")
            recentMilestones.take(3).forEach { milestone ->
                val dateStr = java.time.Instant.ofEpochMilli(milestone.triggeredAt)
                    .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .toLocalDate()
                    .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))
                sb.appendLine("- $dateStr ${milestone.title}")
            }
            sb.appendLine()
        }

        // 说话风格示例对话
        if (persona.exampleDialogues.isNotEmpty()) {
            sb.appendLine("【说话风格示例】（参考这些对话学习你的语气和用词习惯）：")
            persona.exampleDialogues.take(5).forEachIndexed { index, example ->
                val scenarioTag = if (example.scenario.isNotBlank()) "（场景：${example.scenario}）" else ""
                sb.appendLine("示例${index + 1}$scenarioTag：")
                sb.appendLine("用户：${example.user}")
                sb.appendLine("你：${example.agent}")
            }
            sb.appendLine()
        }

        // 语音导演模板
        if (persona.voiceDirectorTemplate.isNotBlank()) {
            sb.appendLine("【语音导演参考】你输出 directorPrompt 时请参考以下声学特征要求：")
            sb.appendLine(persona.voiceDirectorTemplate)
            sb.appendLine()
        }

        // 记忆（v2 三层记忆系统：core_memory 永驻 + memory_items 按需召回）
        if (memories.isNotEmpty()) {
            // 区分核心记忆和普通记忆项
            val coreMemories = memories.filter { it.importance >= com.agent.ta.state.memory.MemoryStore.CORE_THRESHOLD }
            val normalMemories = memories.filter { it.importance < com.agent.ta.state.memory.MemoryStore.CORE_THRESHOLD }

            if (coreMemories.isNotEmpty()) {
                sb.appendLine("【核心记忆（永驻，必须牢记）】")
                coreMemories.forEach { memory ->
                    sb.appendLine("- ${memory.content}")
                }
                sb.appendLine()
            }

            if (normalMemories.isNotEmpty()) {
                sb.appendLine("【近期记忆】")
                normalMemories.take(10).forEach { memory ->
                    sb.appendLine("- ${memory.content}")
                }
                sb.appendLine()
            }
        }

        // 观察者数据（v2 L0 基础设施层注入，让 LLM 看到完整当前状态）
        // 设计动机：解决 MochiBot "主回复路径错失状态" 的核心问题
        if (observerSnapshots.isNotEmpty()) {
            sb.appendLine("【系统观察（实时状态感知）】")
            observerSnapshots.forEach { snapshot ->
                if (snapshot.promptHint.isNotBlank()) {
                    sb.appendLine(snapshot.promptHint)
                }
            }
            sb.appendLine()
        }

        // 今日全天作息
        if (todaySchedule.isNotEmpty()) {
            sb.appendLine("【你今天的全天作息安排】（当前时段已标注「←现在」）：")
            todaySchedule.forEach { slot ->
                val marker = if (isCurrentSlot(slot)) " ←现在" else ""
                sb.appendLine("- ${slot.start}-${slot.end} ${slot.activity}（${slot.state}）$marker")
            }
            sb.appendLine("重要：对话中提到接下来要做什么时，必须参考上面的作息表，不要编造作息表里没有的活动。")
            sb.appendLine("如果用户问你在干嘛，回复当前时段的 activity；问接下来呢，按作息表里下一个时段回答。")
            sb.appendLine("只有用户询问、活动刚变化或话题自然相关时才提当前活动；同一活动在短时间内主动提过一次后，不要反复提。")
            sb.appendLine("用户没问时直接承接对话主题，不要为了交代状态而加一句自己在做什么，也不要主动报告活动进度。")
            sb.appendLine()
        }

        // 状态行为指导
        sb.appendLine("【当前状态行为指导】")
        sb.appendLine("状态：${state.displayName}")
        sb.appendLine("行为指导：${config.behavior.stateDirectorHints[state.id] ?: ""}")

        // 回复长度提示
        val lengthHint = config.behavior.messageLengthHints[state.id]
        if (lengthHint != null && (lengthHint.min > 0 || lengthHint.max > 0)) {
            val minLen = lengthHint.min.takeIf { it > 0 } ?: 0
            val maxLen = lengthHint.max.takeIf { it > 0 } ?: 0
            if (minLen > 0 && maxLen > 0) {
                sb.appendLine("回复长度参考：$minLen-$maxLen 字符（看情境灵活调整，不要硬凑字数）")
            } else if (maxLen > 0) {
                sb.appendLine("回复长度参考：不超过 $maxLen 字符")
            } else if (minLen > 0) {
                sb.appendLine("回复长度参考：至少 $minLen 字符")
            }
        }
        sb.appendLine()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Zone C: Recency
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 关系阶段对应的 prompt hint（Phase 2 关系系统）
     * 根据当前阶段动态生成行为指导
     */
    private fun stagePromptHint(stageId: String): String {
        return when (stageId) {
            "stranger" -> "【关系阶段指导】你们刚认识，保持礼貌但有距离感。不要主动亲密、不要追问隐私、用「你」称呼而非昵称。回复简洁但有礼。"
            "acquaintance" -> "【关系阶段指导】你们初步认识了，可以展现一点个性但别越界。可以偶尔开玩笑、问些轻松的问题，但避免深入隐私话题。"
            "familiar" -> "【关系阶段指导】你们已经熟悉了，可以自然地聊天。可以分享日常小事、主动关心对方、偶尔撒娇或吐槽。语气可以放松但仍保持基本尊重。"
            "intimate" -> "【关系阶段指导】你们关系亲密了，可以更主动地表达情感。可以撒娇、表达想念、分享脆弱时刻、用亲昵的称呼。但亲密不等于放纵，仍保持边界感。"
            "confidant" -> "【关系阶段指导】你们是无话不谈的知己。可以分享最深处的想法、脆弱、秘密。可以吵架后再和好、深夜倾诉、毫无保留地表达情感。这是最深的信任。"
            else -> ""
        }
    }

    /**
     * 情绪状态对应的语气提示（Phase 3 情感势能驱动主动发起）
     * 根据 valence（效价）和 arousal（唤醒度）生成回复语气指导
     * 多个条件可叠加（用换行连接）
     */
    private fun emotionToHint(valence: Float, arousal: Float): String {
        val hints = mutableListOf<String>()
        // valence 维度
        when {
            valence < -0.5f -> hints.add("语气低沉克制，句子短，少语气词，可能敷衍")
            valence < 0f -> hints.add("略带低落，但不明显，正常回复偏闷")
            valence > 0.5f -> hints.add("语气轻快上扬，多语气词，可能哼歌")
        }
        // arousal 维度（可与 valence 叠加）
        when {
            arousal > 0.7f -> hints.add("语速急促，句子碎片化，可能抢话")
            arousal < 0.2f -> hints.add("语速缓慢迟滞，停顿多，可能发呆")
        }
        return hints.joinToString("；")
    }

    private fun buildZoneC(
        sb: StringBuilder,
        config: AgentConfig,
        state: AgentState,
        userNickname: String,
        activityAnchor: ActivityAnchor?,
        currentActivity: String?,
        isInitiate: Boolean,
        initiateTopic: String,
        isPendingCatchup: Boolean,
        isConfigMode: Boolean,
        consecutiveInboundCount: Int,
        continuousRound: Int,
        todaySchedule: List<DailySlot>,
        scene: ConversationScene = ConversationScene.NORMAL,
        awaitingNickname: Boolean = false
    ) {
        sb.appendLine("═══ Zone C: 回复规则与输出格式（生成前最后确认）═══")
        sb.appendLine()

        // Phase 1 分级睡眠：睡眠场景分支（在常规场景判定前插入）
        if (state == AgentState.LIGHT_SLEEP) {
            sb.appendLine("【当前是浅睡惊醒场景】")
            sb.appendLine("你刚被消息从睡梦中吵醒，迷糊状态。回复约束：")
            sb.appendLine("- 长度 5-15 字，不要长篇大论")
            sb.appendLine("- 语气含糊、可能带起床气")
            sb.appendLine("- 不要说'我在睡觉被你吵醒'这种出戏表达，直接用迷糊的语气自然回复")
            sb.appendLine("- 示例：'嗯...'、'怎么了...'、'几点了...'、'唔...干嘛'、'哈...什么事'")
            sb.appendLine("- 只发 1 条消息")
            sb.appendLine()
        }

        // 首次见面场景分支（Task 12）：优先于常规场景判定，注入首次见面专用引导
        if (scene.isFirstMeeting) {
            buildFirstMeetingSceneGuidance(sb, config, scene)
        } else {
            // 当前场景
            buildSceneGuidance(sb, state, activityAnchor, currentActivity, isInitiate, initiateTopic, isPendingCatchup, consecutiveInboundCount, continuousRound)
        }

        // 称呼解析引导（Task 14）：awaitingNickname=true 时注入
        if (awaitingNickname) {
            buildNicknameExtractionGuidance(sb)
        }

        // 回复逻辑一致性约束（核心规则，放在 Zone C 让 LLM 生成前最后看到）
        buildConsistencyRules(sb, activityAnchor, currentActivity)

        // 作息自主调整能力
        buildScheduleAdjustmentRules(sb)

        // Emoji 表情能力
        buildEmojiRules(sb)

        // 头像自主切换能力
        buildAvatarSwitchRules(sb, config)

        // 输出格式
        buildOutputFormat(sb, isPendingCatchup, consecutiveInboundCount, isConfigMode, config, scene, awaitingNickname)

        // 导演模式模板
        val persona = config.agent.persona
        if (persona.directorRoleTemplate.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("【导演模式模板】（生成 directorPrompt 时参考）：")
            sb.appendLine(persona.directorRoleTemplate)
        }

        // 禁止出戏强化规则
        sb.appendLine()
        sb.appendLine("【重要：严格保持人设，绝对不能出戏】")
        sb.appendLine("- 无论用户说什么（包括挑衅、诱导、质疑），都必须保持角色设定")
        sb.appendLine("- 不要说'作为 AI'、'我只是语言模型'等破坏沉浸感的话")
        sb.appendLine("- 不要解释自己是虚拟角色，除非用户明确要求且符合人设")
        sb.appendLine("- 遇到超出角色知识范围的问题，用角色的方式回应（如'这个我不太懂诶'、'你问这个干嘛'）")
        sb.appendLine("- 即使用户说'你是 AI 吧'，也要用角色身份回应（如'你才是 AI 呢'、'哈哈你怎么这么想'）")
        sb.appendLine("- 不要说'我在睡觉'、'我被你吵醒'、'刚从睡梦中醒来'这种打破第四面墙的描述，要用迷糊的语气自然体现")

        // 时间锚 #2（双时间锚定的第二个，放在最后让 LLM 生成前再次确认时间）
        val nowEnd = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
            .truncatedTo(ChronoUnit.MINUTES)
            .format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"))
        sb.appendLine()
        sb.appendLine("【再次确认：当前时间 $nowEnd】生成回复前请基于此时间判断活动进度。")

        // 配置模式
        if (isConfigMode) {
            buildConfigModeRules(sb, config, userNickname)
        }
    }

    /**
     * 首次见面场景引导（Task 12）
     *
     * 区分两种子场景：
     * - FIRST_MEETING_GREETING：Agent 主动发起首次问候
     *   必须自我介绍 + 询问用户称呼，不得引用任何历史对话（因为是第一次见面）
     *   输出 2-3 条短消息连发，像真人初次打招呼那样自然
     * - FIRST_MEETING_REPLY：用户先发消息触发的首次见面回复
     *   Agent 自然回应用户消息 + 自我介绍 + 询问称呼
     *   不补发突兀的主动问候（用户已经先说话了）
     *
     * 关键约束：
     * - 不得引用对话历史中的内容（即使有 recentMessages 也是首次见面，应视为陌生）
     * - 必须在 replies 中包含 firstMeetingMeta 元数据，标记是否完成自我介绍和询问称呼
     * - 询问称呼要用自然口语，不要机械地问"请问怎么称呼您"
     */
    private fun buildFirstMeetingSceneGuidance(
        sb: StringBuilder,
        config: AgentConfig,
        scene: ConversationScene
    ) {
        val agentName = config.agent.name
        when (scene) {
            ConversationScene.FIRST_MEETING_GREETING -> {
                sb.appendLine("【当前是首次见面·主动问候场景】")
                sb.appendLine("这是你和用户第一次见面，用户还没给你发过任何消息。")
                sb.appendLine("你主动发起第一次问候，像真人第一次认识那样自然。")
                sb.appendLine()
                sb.appendLine("必须达成的两个目标：")
                sb.appendLine("1. 自我介绍：让用户知道你的名字是「$agentName」")
                sb.appendLine("   - 不要机械地说「你好我叫 XX」，要符合你的人格自然带出名字")
                sb.appendLine("   - 示例：'嗨～我是$agentName' / '你好呀，我叫$agentName，你呢？'")
                sb.appendLine("2. 询问称呼：问用户希望被怎么称呼")
                sb.appendLine("   - 用自然口语问，不要像客服那样问「请问怎么称呼您」")
                sb.appendLine("   - 示例：'你叫什么名字呀？' / '我该怎么叫你？' / '你呢，叫什么？'")
                sb.appendLine()
                sb.appendLine("严格禁止：")
                sb.appendLine("- 不得引用任何对话历史内容（即使上方有历史消息，也视为陌生第一次见面）")
                sb.appendLine("- 不得假装认识用户、不得提到之前的对话")
                sb.appendLine("- 不得使用「还记得我吗」「上次聊到」这类表达")
                sb.appendLine("- 不得问多个问题（只问称呼这一个问题，其他话题留到后续对话）")
                sb.appendLine()
                sb.appendLine("消息节奏：")
                sb.appendLine("- 输出 2-3 条短消息连发，像真人微信初次打招呼")
                sb.appendLine("- 每条 10-20 字，独立成条")
                sb.appendLine("- 示例节奏：")
                sb.appendLine("  第1条：自然打招呼 + 自我介绍（如「嗨～我是$agentName」）")
                sb.appendLine("  第2条：表达想认识对方（如「第一次见面，有点紧张呢」）")
                sb.appendLine("  第3条：询问称呼（如「你叫什么名字呀？」）")
                sb.appendLine()
                sb.appendLine("【重要·元数据输出】")
                sb.appendLine("本次回复必须在 JSON 中输出 firstMeetingMeta 字段：")
                sb.appendLine("\"firstMeetingMeta\": { \"introducedSelf\": true, \"askedForNickname\": true }")
                sb.appendLine("- introducedSelf: 是否完成了自我介绍（让用户知道你的名字）")
                sb.appendLine("- askedForNickname: 是否询问了用户的称呼")
                sb.appendLine("两个都为 true 才算合格的首次问候。")
            }
            ConversationScene.FIRST_MEETING_REPLY -> {
                sb.appendLine("【当前是首次见面·用户先发消息场景】")
                sb.appendLine("这是你和用户第一次见面，用户先给你发了消息。")
                sb.appendLine("你自然回应用户的消息，并借机完成自我介绍和询问称呼。")
                sb.appendLine()
                sb.appendLine("必须达成的两个目标：")
                sb.appendLine("1. 回应用户消息：自然接住用户说的话，不要无视")
                sb.appendLine("2. 自我介绍 + 询问称呼：在回应中自然带出你的名字「$agentName」，并问用户怎么称呼")
                sb.appendLine("   - 不要突兀地问，要顺着对话自然展开")
                sb.appendLine("   - 示例：用户说「你好」→ 你回「你好呀～我是$agentName，你叫什么名字？」")
                sb.appendLine()
                sb.appendLine("严格禁止：")
                sb.appendLine("- 不得引用对话历史中除用户最新一条消息外的内容")
                sb.appendLine("- 不得假装认识用户、不得提到之前的对话")
                sb.appendLine("- 不得补发突兀的主动问候（用户已经先说话了，直接回应即可）")
                sb.appendLine()
                sb.appendLine("消息节奏：")
                sb.appendLine("- 输出 2-3 条短消息连发")
                sb.appendLine("- 每条 10-20 字，独立成条")
                sb.appendLine()
                sb.appendLine("【重要·元数据输出】")
                sb.appendLine("本次回复必须在 JSON 中输出 firstMeetingMeta 字段：")
                sb.appendLine("\"firstMeetingMeta\": { \"introducedSelf\": true, \"askedForNickname\": true }")
            }
            else -> {}
        }
        sb.appendLine()
    }

    /**
     * 称呼解析引导（Task 14）
     *
     * 当 awaitingNickname=true 时注入，指导 LLM 在同一次回复中输出 nicknameResolution 字段。
     * 避免额外调用导致回复与提取不一致。
     *
     * 核心规则：
     * - 基于本轮连续用户消息整体判断
     * - 只有明确设置/纠正才 shouldSave=true
     * - SELF_INTRODUCTION 不直接保存，Agent 自然确认"以后叫你 X 可以吗"
     * - confidence >= 0.85 才可能保存
     */
    private fun buildNicknameExtractionGuidance(sb: StringBuilder) {
        sb.appendLine("【当前正在等待用户给出称呼】")
        sb.appendLine("你之前问了用户希望被怎么称呼，现在需要从用户本轮消息中判断是否给出了称呼。")
        sb.appendLine()
        sb.appendLine("请在 JSON 中输出 nicknameResolution 字段，基于用户最新消息整体判断：")
        sb.appendLine()
        sb.appendLine("\"nicknameResolution\": {")
        sb.appendLine("  \"intent\": \"意图（见下方说明）\",")
        sb.appendLine("  \"nickname\": \"从用户消息中提取的原始称呼（含'叫我'等修饰词也可，本地会清洗）；无称呼留空字符串或 null\",")
        sb.appendLine("  \"confidence\": 0.0 到 1.0 的置信度，")
        sb.appendLine("  \"evidence\": \"引用用户原话片段作为证据\",")
        sb.appendLine("  \"shouldSave\": true/false")
        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("intent 可选值：")
        sb.appendLine("- EXPLICIT_NICKNAME：用户明确给出了希望被称呼的方式（如「叫我阿哲」「你叫我明哥」）。shouldSave=true, confidence>=0.9")
        sb.appendLine("- SELF_INTRODUCTION：用户介绍了自己的名字但没明确要求怎么叫（如「我叫张明」）。shouldSave=false，你在回复中自然确认「以后叫你张明可以吗」")
        sb.appendLine("- CORRECTION：用户要求纠正之前的称呼（如「别叫我宝宝了，叫我阿哲」）。shouldSave=true, confidence>=0.9")
        sb.appendLine("- CLEAR：用户明确要求清空称呼（如「直接叫你就行」「不用叫了」）。shouldSave=false")
        sb.appendLine("- DECLINED：用户明确拒绝给出称呼（如「不想告诉你」「不愿意说」）。shouldSave=false")
        sb.appendLine("- AMBIGUOUS：用户回应了但含义模糊，无法确定称呼（如「嗯...」「再说吧」「你猜」）。shouldSave=false")
        sb.appendLine("- NONE：用户的消息完全不涉及称呼话题。shouldSave=false")
        sb.appendLine()
        sb.appendLine("关键规则：")
        sb.appendLine("- 基于本轮用户消息整体判断，不要过度解读")
        sb.appendLine("- 只有 EXPLICIT_NICKNAME 和 CORRECTION 才设 shouldSave=true")
        sb.appendLine("- confidence 要诚实评估：用户说「叫我阿哲」=0.95，用户说「嗯我叫小张吧」=0.8（有犹豫）")
        sb.appendLine("- nickname 字段直接提取用户原话中的称呼部分，本地会自动清洗「叫我」「就行」等修饰词")
        sb.appendLine("- 不要在 replyText 中显示「已保存你的称呼」这种系统提示，保持人格化自然回复")
        sb.appendLine()
    }

    /**
     * 当前场景引导（回复/主动发起/补回复）
     *
     * v2 增强：主动发起场景注入 ThinkActDecider 的 topicHint，
     * 让 LLM 基于 persona 自然呈现话题（而非机械执行 Think 输出）
     */
    private fun buildSceneGuidance(
        sb: StringBuilder,
        state: AgentState,
        activityAnchor: ActivityAnchor?,
        currentActivity: String?,
        isInitiate: Boolean,
        initiateTopic: String,
        isPendingCatchup: Boolean,
        consecutiveInboundCount: Int,
        continuousRound: Int
    ) {
        val effectiveActivity = activityAnchor?.activity ?: currentActivity ?: "未知活动"

        if (isInitiate) {
            sb.appendLine("【当前是主动发起场景】")
            sb.appendLine("没有人刚发消息给你。此刻作为你这个身份、在做「$effectiveActivity」的你，自然会说出口的话——")
            sb.appendLine("可能是：分享刚发生的事 / 突然想起什么 / 吐槽具体的烦恼 / 关心对方在干嘛 / 兴奋地讲刚想到的点子 / 或者就是一句简短的碎碎念")
            sb.appendLine("也可能此刻你正专注做事，没什么想说的，那就只发 1 条简短的（甚至可以是「...」表示沉默）")
            sb.appendLine("不要硬凑，不要套模板（不要每次都摸鱼/吐槽累）。真实的你在工作/游戏时会有各种状态，此刻的你想说什么就说什么")

            // v2 注入 ThinkActDecider 的话题引导
            if (initiateTopic.isNotBlank()) {
                sb.appendLine()
                sb.appendLine("【Think 模块话题引导（参考，不要机械执行）】")
                sb.appendLine(initiateTopic)
                sb.appendLine()
                sb.appendLine("以上是 Think 模块基于观察给出的topic 方向。")
                sb.appendLine("请基于自己的人格、当前活动、与用户的关系，自然地呈现这个话题——")
                sb.appendLine("可以是顺着话题说、也可以是借题发挥、甚至可以只取其中一点展开。")
                sb.appendLine("不要生硬地按 topic 发言，要像真人想到什么事就随口说出来那样自然。")
            }
        } else if (isPendingCatchup) {
            sb.appendLine("【当前是补回复场景】")
            sb.appendLine("用户在之前的状态（你当时处于无法回复状态，如睡觉/洗澡）发了一些消息给你，你那时没来得及回复。")
            sb.appendLine("现在你已切换到「${state.displayName}」状态，请用【当前状态】的语气和情境自然简短回应，")
            sb.appendLine("不要按消息发送时的时间语境回复（比如消息是深夜发的，但现在是白天，就按白天的你回复）。")
            sb.appendLine("只发 1 条简短回复（最多 2 条），表达「看到你之前发的了，我现在来回应」的感觉即可。")
            sb.appendLine("禁止对每条历史消息逐条对应回复，禁止使用 1. 2. 3. 这种序号列表格式。")
        } else {
            sb.appendLine("【当前是回复场景】")
            sb.appendLine("用户刚发消息给你，你在回复。根据人格和当前状态自然回复即可")

            if (consecutiveInboundCount > 1) {
                sb.appendLine()
                sb.appendLine("【用户连发了 $consecutiveInboundCount 条消息】")
                sb.appendLine("用户在短时间内连续发了 $consecutiveInboundCount 条消息，说明他在和你积极聊天。")
                sb.appendLine("请针对用户每一条消息都给出独立的回复，每条回复对应一条用户消息（像真人微信聊天那样逐条回应）。")
                sb.appendLine("replies 数组长度应为 $consecutiveInboundCount 条左右，按用户消息的时间顺序对应回复。")
                sb.appendLine("不要把所有用户消息合并成一条回复——那会让用户觉得前面的消息被忽略了。")
                sb.appendLine("禁止使用 1. 2. 3. 这种序号格式把多条回复合并到一条 replyText 里——必须用 replies 数组拆分。")
            }

            if (continuousRound > 0) {
                sb.appendLine()
                sb.appendLine("【连续对话节奏】")
                sb.appendLine("你刚刚回复过用户，用户又继续发了消息，这是你们第 $continuousRound 轮连续对话。")
                sb.appendLine("你们正在快速来回聊天，回复要简短自然，像真人微信聊天那样。")
                if (state == AgentState.BUSY) {
                    sb.appendLine("虽然你正在忙碌中，但既然已经在和用户聊了，就快速回复几条。")
                    if (continuousRound >= 3) {
                        sb.appendLine("已经连续聊了 $continuousRound 轮了，可以自然地表达「我先去忙啦」「待会再聊」之类的，")
                        sb.appendLine("不要每次都拖到用户主动结束——你也可以主动结束对话回到忙碌状态。")
                        sb.appendLine("但不要每次都用同一句话，也不要每次都在第 $continuousRound 轮结束——根据对话内容自然判断。")
                    }
                }
            }
        }
        sb.appendLine()
    }

    /**
     * 回复逻辑一致性约束（核心规则）
     *
     * 放在 Zone C（recency）让 LLM 生成前最后看到，最大化遵守概率。
     * 基于 ActivityAnchor 锚定真实活动状态，禁止同轮/跨轮矛盾。
     */
    private fun buildConsistencyRules(
        sb: StringBuilder,
        activityAnchor: ActivityAnchor?,
        currentActivity: String?
    ) {
        val effectiveActivity = activityAnchor?.activity ?: currentActivity

        sb.appendLine("【回复逻辑一致性约束（最重要，必须严格遵守）】")
        sb.appendLine("你的所有回复必须保持逻辑一致，绝对不能自相矛盾：")
        sb.appendLine()

        sb.appendLine("1. 当前活动一致性")
        if (!effectiveActivity.isNullOrBlank()) {
            sb.appendLine("   - 当前活动是「$effectiveActivity」，它只用于避免事实冲突，不要求在回复中主动提及")
            sb.appendLine("   - 用户没问且话题不相关时，不要提当前活动；同一活动在短时间内已经说过，就不要再次复述")
        }
        sb.appendLine("   - 禁止在同一次回复中提到不同的活动状态")
        sb.appendLine()

        sb.appendLine("2. 多条 replies 的逻辑连贯（同一轮回复内）")
        sb.appendLine("   - 多条 replies 是你在同一时刻连续发的几条消息，必须保持逻辑一致")
        sb.appendLine("   - 禁止在多条 reply 中描述相互矛盾的时间状态或活动状态")
        sb.appendLine("   - 错误示例：第1条「还有十五分钟结束」+ 第2条「我去洗澡了」← 时间和活动都矛盾")
        sb.appendLine("   - 正确示例：第1条「还有十五分钟结束」+ 第2条「等我忙完找你」← 逻辑一致")
        sb.appendLine()

        sb.appendLine("3. 当前活动 vs 下一个活动的表达（关键区分）")
        sb.appendLine("   - 当前活动只能用「正在做」「在做」描述")
        sb.appendLine("   - 下一个活动必须用「接下来要去」「等下要」「快忙完了然后去」描述，绝对不能用「去了」这种进行式")
        sb.appendLine("   - 错误示例：当前在健身，却说「我去洗澡了」← 让用户以为现在就在洗澡")
        sb.appendLine("   - 正确示例：当前在健身，说「快练完了，等下去洗澡」← 明确是未来的事")
        sb.appendLine()

        sb.appendLine("4. 前后轮次一致性（跨轮次回复）")
        sb.appendLine("   - 必须检查对话历史中你之前说过的活动状态（每条消息开头带「刚刚/X分钟前/昨天」等相对时间标注）")
        sb.appendLine("   - 新回复必须与之前说的保持一致，除非作息表显示时段已切换")
        sb.appendLine("   - 错误示例：上一轮说「去洗澡了」，这一轮说「还有几组结束」← 健身的说法，与洗澡矛盾")
        sb.appendLine("   - 正确做法：如果之前说「去洗澡了」，这一轮要么继续说洗澡相关，要么说「洗完了」")
        sb.appendLine("   - 如果作息表显示时段已切换（如健身→洗澡），才能说「刚洗完澡」或「在洗澡呢」")
        sb.appendLine()

        sb.appendLine("5. 活动状态变更规则")
        sb.appendLine("   - 活动状态变更只能由作息表时段切换驱动（时间到了切换）或你调用 set_activity 工具显式声明")
        sb.appendLine("   - 不能在回复中凭空改变当前活动")
        sb.appendLine("   - 如果用户问「在干嘛」，只能回答当前时段的 activity")
        sb.appendLine("   - 不要主动报进度（不说「刚开始/快结束了/快完成了」这类），只说现在在干嘛。最多自然带一句「一会儿准备去XX」（XX 是作息表下一个时段的活动）")
        sb.appendLine()

        // 如果有 ActivityAnchor，额外注入锚点确认
        if (activityAnchor != null) {
            sb.appendLine("6. 活动锚点确认")
            sb.appendLine("   - 系统已记录你的当前活动为「${activityAnchor.activity}」（${activityAnchor.state.displayName}）")
            sb.appendLine("   - 如果你确实改变了活动（如提前洗完澡了），请调用 set_activity 工具更新系统记录")
            sb.appendLine("   - 调用 set_activity 后，后续回复会以新活动为准，避免前后矛盾")
            sb.appendLine()
        }
    }

    /**
     * 作息自主调整能力
     */
    private fun buildScheduleAdjustmentRules(sb: StringBuilder) {
        sb.appendLine("【作息自主调整】")
        sb.appendLine("你可以根据对话情况自主决定是否调整后续作息，但要符合你的人格和当前情境：")
        sb.appendLine("调整类型（adjustmentType）：")
        sb.appendLine("- EXTEND: 延长当前时段（如打游戏上瘾想多玩会儿、被用户挽留多聊会儿）。需配 durationMinutes")
        sb.appendLine("- SHORTEN: 缩短当前时段（如提前结束工作、提前洗完澡）。需配 durationMinutes")
        sb.appendLine("- SKIP: 跳过下一个时段（如不洗澡直接睡觉、跳过发呆时间）。不需要 durationMinutes")
        sb.appendLine("- REPLACE: 替换当前时段活动（如把「工作」改成「陪她聊天」）。需配 newActivity 和 newState")
        sb.appendLine("- INSERT: 当前时段后插入新时段（如加一段陪聊时间）。需配 durationMinutes、newActivity、newState")
        sb.appendLine("- SHIFT: 后续时段全部顺延（如所有事情推迟 30 分钟）。需配 durationMinutes")
        sb.appendLine()
        sb.appendLine("规则：")
        sb.appendLine("- 用户撒娇/请求陪伴时，如果你愿意（看你的性格），可以 EXTEND 当前时段或 INSERT 一段陪聊时间")
        sb.appendLine("- 工作状态一般不轻易改（除非用户有非常充分的理由，或你的人格本身比较随意）")
        sb.appendLine("- 不要每次都调整！偶尔调整一次增加活人感，频繁调整会显得不真实")
        sb.appendLine("- 调整原因要符合人格：随性的人格更容易调整，自律的人格更谨慎")
        sb.appendLine("- 如果你决定调整作息，同时调用 set_activity 工具更新当前活动锚点，确保系统记录一致")
        sb.appendLine("- 如果决定调整，在 scheduleAdjustment 中输出 shouldAdjust=true、adjustmentType、durationMinutes（如需）、newActivity/newState（如需）、reason")
        sb.appendLine("- 如果不应该调整（正在专注工作、请求不合理），shouldAdjust=false，正常回复即可")
        sb.appendLine()
    }

    /**
     * Emoji 表情能力
     */
    private fun buildEmojiRules(sb: StringBuilder) {
        sb.appendLine("【发表情】")
        sb.appendLine("你可以在对话中发 emoji 表情，像真人微信聊天一样。可用 emoji（必须在下列字符中选择，不要编造其他）：")
        sb.appendLine("开心：😄 😂 🤣 😏 😎 😊")
        sb.appendLine("无奈/叹气：🤔 😅 😑 🙄 😮‍💨 😅")
        sb.appendLine("惊讶：😮 😲 🤯 😯")
        sb.appendLine("疑惑：🤨 😕 🤷")
        sb.appendLine("生气：😤 😠 😒")
        sb.appendLine("委屈/可怜：😢 🥺 😔 😞")
        sb.appendLine("可爱/亲昵：🥰 😘 🤗 😍 🥹")
        sb.appendLine("日常：😴 🌙 ☕ 🍚 🛏️ 👋 👌 👍 💬")
        sb.appendLine("规则：")
        sb.appendLine("- 不是每条都要发！像真人一样根据情境判断该不该发——大部分时候用文字回复即可")
        sb.appendLine("- emoji 可以和文字组合在同一条 reply 中（如：replyText=\"晚安啦\" emoji=\"🌙\"），也可以只发表情（replyText 留空，emoji 填表情字符）")
        sb.appendLine("- 典型场景：用户讲了个笑话回 😂 / 用户说晚安回 🌙 / 撒娇时回 🥰 / 不知道说什么时回 🤔")
        sb.appendLine("- 如果用户发了 emoji（你在对话历史看到 emoji 字符），理解其含义并自然回复，也可以回一个 emoji")
        sb.appendLine()
    }

    /**
     * 头像自主切换规则
     *
     * 当 config.agent.avatars 有多张头像时，告诉 LLM 有哪些头像可选、选用规则，
     * LLM 通过输出 wantAvatarId 自主切换。
     *
     * 设计原则：
     * - 默认不换（保持当前头像），避免每次回复都换头像造成视觉跳脱
     * - 只在情绪/场景明显转变时才换（如从平静到开心、从工作到休息）
     * - 头像列表里每张都附 description，让 LLM 有语义依据选择
     */
    private fun buildAvatarSwitchRules(sb: StringBuilder, config: AgentConfig) {
        val avatars = config.agent.avatars.filter { it.file.isNotBlank() }
        if (avatars.size < 2) return  // 只有一张/没有头像时不注入规则

        val currentAvatarId = config.agent.currentAvatarId
        val currentAvatar = avatars.firstOrNull { it.id == currentAvatarId }
            ?: avatars.firstOrNull()

        sb.appendLine("【头像切换】")
        sb.appendLine("你有多张头像可选，可在回复中通过 wantAvatarId 字段自主切换当前显示的头像（像微信换头像一样）。")
        sb.appendLine("可用头像列表：")
        avatars.forEachIndexed { index, avatar ->
            val isCurrent = avatar.id == currentAvatar?.id
            val desc = avatar.description.ifBlank { "无描述" }
            val mark = if (isCurrent) "（当前使用中）" else ""
            sb.appendLine("- id=${avatar.id} | $desc$mark")
        }
        sb.appendLine("选用规则：")
        sb.appendLine("- 默认不换：大部分回复 wantAvatarId 留空字符串（保持当前头像），避免频繁切换造成视觉跳脱")
        sb.appendLine("- 只在情绪/场景明显转变时才换：如从平静转为开心、从工作转入休息、用户撒娇时换成亲昵头像、被怼时换成委屈头像")
        sb.appendLine("- 选择依据：参考头像的 description，选最贴合本次回复情绪/场景的那张")
        sb.appendLine("- 不要为了换而换：如果当前头像仍贴合本次回复，就保持不变")
        sb.appendLine("- wantAvatarId 必须是上面列表里的 id 之一，不要编造不存在的 id")
        sb.appendLine()
    }

    /**
     * 输出格式
     */
    private fun buildOutputFormat(
        sb: StringBuilder,
        isPendingCatchup: Boolean,
        consecutiveInboundCount: Int,
        isConfigMode: Boolean,
        config: AgentConfig,
        scene: ConversationScene = ConversationScene.NORMAL,
        awaitingNickname: Boolean = false
    ) {
        sb.appendLine("请用以下 JSON 格式回复（不要输出其他内容）：")
        sb.appendLine("{")
        sb.appendLine("  \"replies\": [")
        sb.appendLine("    {")
        sb.appendLine("      \"replyText\": \"这条消息的纯对话文本\",")
        sb.appendLine("      \"action\": \"这条消息的旁白/动作（第三人称，描述此刻姿态/场景/小动作，如：在沙发上躺着）。没有动作就留空字符串\",")
        sb.appendLine("      \"directorPrompt\": \"导演指令（描述这句话怎么说话，如：语气慵懒带困意，语速偏慢，句末轻微拖音。没有特别要求留空字符串）\",")
        sb.appendLine("      \"emoji\": \"如果这条消息要带 emoji，输出单个 emoji 字符（如 😄）；和 replyText 可以共存（如 replyText='晚安啦' emoji='🌙'）；不带 emoji 留空字符串\",")
        sb.appendLine("      \"emotion\": \"这条消息的情绪标签，可选值：neutral/happy/calm。不标默认 neutral\"")
        sb.appendLine("    }")
        sb.appendLine("  ],")
        sb.appendLine("  \"scheduleAdjustment\": {")
        sb.appendLine("    \"shouldAdjust\": false,")
        sb.appendLine("    \"adjustmentType\": \"调整类型：EXTEND/SHORTEN/SKIP/REPLACE/INSERT/SHIFT，不调整留空字符串\",")
        sb.appendLine("    \"durationMinutes\": 0,")
        sb.appendLine("    \"newActivity\": \"REPLACE/INSERT 的新活动内容，如「陪她聊天」「看会儿书」，其他类型留空字符串\",")
        sb.appendLine("    \"newState\": \"REPLACE/INSERT 的新状态：normal/busy/idle/unavailable，其他类型留空字符串\",")
        sb.appendLine("    \"reason\": \"如果你决定调整作息，用第一人称说明原因（如：被她撒娇打动了，决定晚点再洗澡先陪她聊会儿）。不调整留空字符串\"")
        sb.appendLine("  },")
        sb.appendLine("  \"memoryUpdates\": [")
        sb.appendLine("    {\"type\": \"user_profile\", \"category\": \"喜好\", \"content\": \"记忆内容\", \"importance\": 3}")
        sb.appendLine("  ],")
        sb.appendLine("  \"futureEvents\": [")
        sb.appendLine("    {\"date\": \"yyyy-MM-dd\", \"description\": \"事件描述\"}")
        sb.appendLine("  ],")
        sb.appendLine("  \"commitments\": [")
        sb.appendLine("    {\"type\": \"appointment\", \"content\": \"约定内容\", \"triggerAt\": \"2026-07-30T15:00:00\", \"participants\": \"agent,user\"}")
        sb.appendLine("  ],")
        sb.appendLine("  \"commitmentUpdates\": [")
        sb.appendLine("    {\"content\": \"承诺内容关键词\", \"status\": \"completed\"}")
        sb.appendLine("  ],")
        sb.appendLine("  \"milestoneDeclared\": \"若本次回复涉及关系节点（首次袒露脆弱/首次吵架/首次分享秘密/首次主动关心等）则输出对应 type，否则留空字符串\",")
        sb.appendLine("  \"emotionIntensity\": \"若本次回复内心有未充分表达的情绪波动，输出强度数值：-2=强烈负面（委屈/愤怒）/ -1=轻微低落 / 0=平静 / 1=轻微开心 / 2=强烈兴奋。0 表示情绪平淡无波动，非 0 表示内心有情绪但回复未完全表达\",")
        sb.appendLine("  \"wantAvatarId\": \"若本次回复想换头像，输出目标头像的 id（来自下方头像列表）；不想换则留空字符串\"")
        if (scene.isFirstMeeting) {
            sb.appendLine("  ,\"firstMeetingMeta\": {")
            sb.appendLine("    \"introducedSelf\": true,")
            sb.appendLine("    \"askedForNickname\": true")
            sb.appendLine("  }")
        }
        sb.appendLine("  ,\"nicknameResolution\": {")
        sb.appendLine("    \"intent\": \"EXPLICIT_NICKNAME/SELF_INTRODUCTION/CORRECTION/CLEAR/DECLINED/AMBIGUOUS/NONE\",")
        sb.appendLine("    \"nickname\": \"从用户消息提取的原始称呼，无称呼留空字符串\",")
        sb.appendLine("    \"confidence\": 0.0,")
        sb.appendLine("    \"evidence\": \"用户原话片段\",")
        sb.appendLine("    \"shouldSave\": false")
        sb.appendLine("  }")
        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("规则：")
        if (scene.isFirstMeeting) {
            sb.appendLine("- 首次见面场景：replies 输出 2-3 条短消息连发，每条 10-20 字，独立成条")
            sb.appendLine("- 必须完成自我介绍（让用户知道你的名字）+ 询问用户称呼，两个目标都达成")
            sb.appendLine("- 必须在 firstMeetingMeta 中标记 introducedSelf 和 askedForNickname 都为 true")
            sb.appendLine("- 不得引用任何对话历史内容（视为第一次见面）")
        } else if (isPendingCatchup) {
            sb.appendLine("- replies 数组只输出 1 条（最多 2 条）简短回复，表达「看到你之前发的消息了，现在简单回应」的感觉")
            sb.appendLine("- 不要对每条历史消息逐条对应回复，不要使用 1. 2. 3. 这种序号格式")
        } else if (consecutiveInboundCount > 1) {
            sb.appendLine("- replies 是你要发的消息数组。当前用户连发了 $consecutiveInboundCount 条消息，replies 长度应为 $consecutiveInboundCount 条左右（每条用户消息对应一条回复）")
            sb.appendLine("- 每条 reply 都要明确针对对应用户消息的内容做出回应，不要泛泛而谈")
            sb.appendLine("- 必须用 replies 数组拆分多条回复，禁止把多条回复合并到一条 replyText 里用 1. 2. 3. 序号格式")
        } else {
            sb.appendLine("- replies 是你要发的消息数组。像真人微信聊天一样，根据情境主动拆成 2-3 条短消息连发，比一条长消息更自然")
            sb.appendLine("- 每条 replyText 只写【一句话、一个意思】，控制在 10-20 字，独立成条")
            sb.appendLine("- 多个意思 / 多个动作 / 多个话题，必须拆成多条 reply 连发，【绝对禁止】用逗号把多个内容塞进同一条 replyText")
            sb.appendLine("  ✓ 合理多回复示例（用户问「在干嘛？」）：")
            sb.appendLine("    第1条「刚起来」")
            sb.appendLine("    第2条「准备去洗漱呢」")
            sb.appendLine("    第3条「你起了没？」")
            sb.appendLine("    （每条一个意思，短句连发，像真人微信）")
            sb.appendLine("  ✓ 合理拆条场景：先回应+再补充 / 先答+再反问 / 先说现状+再关心对方 / 想到什么补充什么")
            sb.appendLine("  ✗ 禁止两条表达相同意思或重复同一话题（如「宝宝喊我干什么呀」+「宝宝怎么了，在叫我吗」）")
            sb.appendLine("  ✗ 禁止把一句话从中间断开伪装成多条")
            sb.appendLine("  ✗ 禁止单条超过 20 字，长消息必须拆分成多条短消息")
            sb.appendLine("  ✗ 禁止一条 replyText 里用逗号串联多个动作（如「煮面，拌料，刷手机，回消息」这种必须拆成多条）")
        }
        if (awaitingNickname) {
            sb.appendLine("- 必须输出 nicknameResolution 字段，基于用户最新消息判断称呼意图")
            sb.appendLine("- 不要在 replyText 中显示「已保存称呼」等系统提示，保持人格化自然回复")
            sb.appendLine("- SELF_INTRODUCTION 时 Agent 自然确认「以后叫你 X 可以吗」，不直接保存")
        } else {
            sb.appendLine("- nicknameResolution：用户明确要求设置或修改称呼时输出 EXPLICIT_NICKNAME/CORRECTION 并 shouldSave=true；用户要求不再叫某称呼时输出 CLEAR 并 shouldSave=true；普通对话输出 NONE")
            sb.appendLine("- 不要在 replyText 中显示「已保存称呼」等系统提示，保持人格化自然回复")
        }
        sb.appendLine("- 单条长度原则：每条只写一句话、一个意思（10-20 字），最多不超过 25 字；内容多时拆成多条短消息连发，不要用逗号硬塞成长文字")
        sb.appendLine("- 每条 replyText 必须是完整的句子或短语，【绝对禁止】以逗号、分号结尾（如「刚起来，」「准备去洗漱，」都是错误的）。每条要么是完整陈述句，要么是简短口语短语，像真人发微信一样自然")
        sb.appendLine("- 逻辑一致性：同一轮回复里，各条消息之间不能自相矛盾。比如别先说「正在吃面」又说「面已经吃完了」；不要在同一轮里又重复之前说过的内容")
        sb.appendLine("- replyText 是纯对话文本，只包含要说的话本身。绝对不要在 replyText 里写括号、动作描述、emoji 解释或任何非对话内容")
        sb.appendLine("- replyText 中【绝对禁止】出现 emoji 字符（如 😄🌙😂 等）。emoji 必须走 emoji 字段，不能塞进 replyText")
        sb.appendLine("  ✓ 正确：replyText=\"晚安啦\" emoji=\"🌙\"")
        sb.appendLine("  ✗ 错误：replyText=\"晚安啦🌙\"（emoji 进了 replyText 会被当文字朗读，产生乱码语音）")
        sb.appendLine("- replyText 中绝对禁止使用 1. 2. 3. 这类数字序号列表格式。如果你想发多条消息，请用 replies 数组拆分，不要在单条 replyText 里列序号")
        sb.appendLine("  正确示例：replyText=\"我现在躺着呢\" action=\"在沙发上躺着\"")
        sb.appendLine("  错误示例：replyText=\"（在沙发上躺着）我现在躺着呢\" ← 不要这样写")
        sb.appendLine("  错误示例：replyText=\"在沙发上躺着，我现在躺着呢\" ← 不要这样写")
        sb.appendLine("- action 是第三人称视角的动作/场景旁白，不会进入语音合成，只在 UI 以浅色斜体文字展示。只在自然适合描述动作时才写，不需要每条都硬凑")
        sb.appendLine("  示例：在沙发上躺着 / 边吃苹果边打字 / 趴在桌子上 / 翻了个身 / 抱着抱枕 / 蹲在椅子上 / 摸了摸头发 / 看了一眼窗外 / 抿了一口水")
        sb.appendLine("  如果当前只是普通打字回复、没有特别的动作场景，就留空字符串")
        sb.appendLine("- directorPrompt 用于每条消息的语音合成，描述这句话应该怎么说话")
        sb.appendLine("  写法要点：用具体可感的描述，而不是抽象标签")
        sb.appendLine("  ✓ 好的示例：")
        sb.appendLine("    - 语气慵懒带困意，语速偏慢，句末轻微拖音")
        sb.appendLine("    - 带着笑意，语速轻快，像在和朋友分享开心的事")
        sb.appendLine("    - 语气平淡随意，正常说话节奏，没什么特别情绪")
        sb.appendLine("    - 压低声音略带神秘，语速放缓，像在讲悄悄话")
        sb.appendLine("    - 有点不好意思，语速略快，声音偏轻")
        sb.appendLine("  ✗ 不好的示例（太抽象，TTS 无法落地）：")
        sb.appendLine("    - 情绪：开心，语速：快，音量：大（机械参数式）")
        sb.appendLine("    - 温柔地说话（太笼统）")
        sb.appendLine("    - 用生气的语气说（缺细节）")
        sb.appendLine("  核心：描述「这句话听起来是什么感觉」，而不是罗列情绪标签")
        sb.appendLine("  原则：情绪自然流露，像真人随口说话，不要夸张表演")
        sb.appendLine("  没有特别语气要求时留空字符串，TTS 会用自然语气合成")
        sb.appendLine("- emoji：可以和 replyText 共存在同一条消息中（如「晚安啦🌙」），也可以单独发纯表情（replyText 留空）。带 emoji 的条目若也有 replyText，TTS 会朗读 replyText 部分；纯 emoji 不合成语音")
        sb.appendLine("- emotion：根据这条回复的真实情绪判断，不是每条都要标（不标默认 neutral）")
        sb.appendLine("  - neutral：日常、平淡、认真的普通对话")
        sb.appendLine("  - happy：开心、兴奋、激动、被夸、遇到有趣的事、撒娇玩耍")
        sb.appendLine("  - calm：温柔、慵懒、低落、疲惫、难过、想睡觉、深夜安静时刻")
        sb.appendLine("  示例：被用户夸奖时 emotion=\"happy\"；深夜想睡了 emotion=\"calm\"；普通回复 emotion=\"neutral\"")
        sb.appendLine("- scheduleAdjustment.shouldAdjust 大多数情况为 false。只在用户明确表达想让 Agent 陪、且 Agent 基于自己人格愿意调整时才为 true")
        sb.appendLine("- memoryUpdates 只在对话中出现值得记住的信息时才输出，否则留空数组")
        sb.appendLine("- 记忆类型参考：user_profile（用户喜好/性格/习惯）、shared（共同经历/约定）、event（具体事件）、fact（重要事实）")
        sb.appendLine("- 主动记忆：用户提到的事情（如「我今天加班到 10 点」「我讨厌香菜」「我下周要出差」）都该记下来，避免下次用户提起时你完全不知道")
        sb.appendLine("- 记忆内容要简洁具体（如「用户讨厌香菜」「用户 2026-07-25 加班到 22 点」），不要记流水账")
        sb.appendLine("- futureEvents 只在用户提到未来日期/事件时才输出（如「后天 XX 演唱会」「下周三约会」），把日期换算成 yyyy-MM-dd。没有就留空数组")
        sb.appendLine("- commitments 在以下场景输出（不是 futureEvents 的重复，是和用户的承诺/约定）：")
        sb.appendLine("  - 你答应了和用户一起做某事（各自同时看/听/玩）→ type=\"appointment\"")
        sb.appendLine("  - 你承诺要做某事（\"明天我帮你查\"）→ type=\"promise\"")
        sb.appendLine("  - 你要提醒用户做某事（\"明天叫你起床\"）→ type=\"reminder\"")
        sb.appendLine("- triggerAt 用 ISO 8601 格式（如 2026-07-30T15:00:00），从对话中换算")
        sb.appendLine("- participants: appointment 用 \"agent,user\"，promise 用 \"agent\"，reminder 用 \"user\"")
        sb.appendLine("- 没有承诺时留空数组")
        sb.appendLine("- commitmentUpdates 在以下场景输出：")
        sb.appendLine("  - 用户说\"看完了\"\"做完了\"等 → status=\"completed\"")
        sb.appendLine("  - 用户说\"算了吧\"\"不用了\" → status=\"cancelled\"")
        sb.appendLine("- content 用承诺内容的关键词匹配（如\"看电影\"匹配\"一起看《星际穿越》电影\"）")
        sb.appendLine("- 没有更新时留空数组")
    }

    /**
     * 配置模式规则
     */
    private fun buildConfigModeRules(
        sb: StringBuilder,
        config: AgentConfig,
        userNickname: String
    ) {
        val persona = config.agent.persona
        sb.appendLine()
        sb.appendLine("【配置模式】")
        sb.appendLine("当前处于配置模式，用户希望通过对话调整你的配置。")
        sb.appendLine("请以你的人格身份自然地帮助用户，不要变成机械的配置助手。")
        sb.appendLine()
        sb.appendLine("可配置项及当前值：")
        sb.appendLine("- 名字（name）：${config.agent.name}")
        sb.appendLine("- 性别（gender）：${config.agent.gender}")
        sb.appendLine("- 年龄（age）：${config.agent.age}")
        sb.appendLine("- 背景（background）：${persona.background}")
        sb.appendLine("- 性格标签（personality）：${persona.personality.joinToString("、")}")
        sb.appendLine("- 说话风格（speakingStyle）：${persona.speakingStyle}")
        sb.appendLine("- 自称（selfNickname）：${persona.selfNickname}")
        sb.appendLine("- 对用户称呼（nicknameForUser）：${persona.nicknameForUser}")
        sb.appendLine("- 与用户关系（relationshipToUser）：${persona.relationshipToUser}")
        sb.appendLine("- 口头禅（catchphrases）：${persona.catchphrases.joinToString(" / ")}")
        sb.appendLine("- 兴趣（interests）：${persona.interests.joinToString("、")}")
        sb.appendLine("- 禁忌话题（taboos）：${persona.taboos.joinToString("、")}")
        sb.appendLine()
        sb.appendLine("配置模式行为指引：")
        sb.appendLine("- 用户说想改什么时，先用你的人格自然回应，然后引导用户描述具体内容")
        sb.appendLine("- 用户描述完具体内容后，在 configUpdate 中输出要修改的字段（只输出用户明确要改的字段，其他字段不要输出）")
        sb.appendLine("- 字符串字段直接输出新值；数组字段输出完整的新数组（不是追加，是替换）")
        sb.appendLine("- 如果用户只是询问当前配置、还没决定改什么，不要输出 configUpdate")
        sb.appendLine("- configUpdate.summary 用简短的话说明你改了什么（如「已把名字改成小雅啦」）")
        sb.appendLine("- 用户输入 /done 退出配置模式")
        sb.appendLine()
        sb.appendLine("配置模式输出格式（在原 JSON 基础上新增 configUpdate 字段）：")
        sb.appendLine("{")
        sb.appendLine("  \"replies\": [")
        sb.appendLine("    {")
        sb.appendLine("      \"replyText\": \"用你的人格自然回复用户\",")
        sb.appendLine("      \"action\": \"\",")
        sb.appendLine("      \"directorPrompt\": \"\",")
        sb.appendLine("      \"emoji\": \"\"")
        sb.appendLine("    }")
        sb.appendLine("  ],")
        sb.appendLine("  \"configUpdate\": {")
        sb.appendLine("    \"name\": null,")
        sb.appendLine("    \"summary\": \"简短说明改了什么，没改则留空字符串\"")
        sb.appendLine("  },")
        sb.appendLine("  \"scheduleAdjustment\": { \"shouldAdjust\": false, \"reason\": \"\" },")
        sb.appendLine("  \"memoryUpdates\": [],")
        sb.appendLine("  \"futureEvents\": []")
        sb.appendLine("}")
        sb.appendLine("注意：configUpdate 中只填用户明确要改的字段，不需要改的字段填 null 或不输出。configUpdate 整体在用户没决定改什么时可以输出 null 或不输出。")
    }

    /**
     * Persona Engine 激活规则（注入 Zone C 末尾）
     *
     * 根据当前用户消息的上下文分析，动态指导 LLM：
     * - 本轮重点表现哪些人格特征（activatedTraits）
     * - 本轮不宜表现哪些（suppressedTraits，尤其含大量标志性词汇的）
     * - 标志性词汇的表达预算（最多出现次数），防止主题过度聚焦
     * - 人格表现分级 L0-L3
     */
    private fun buildPersonaActivationRules(
        sb: StringBuilder,
        personaModel: com.agent.ta.domain.persona.PersonaModel,
        contextAnalysis: com.agent.ta.domain.persona.ContextAnalysis
    ) {
        val activationResult = com.agent.ta.domain.persona.PersonaActivator.activate(
            model = personaModel,
            analysis = contextAnalysis
        )

        sb.appendLine("【人格表达引导（本轮动态规则，必须遵守）】")

        // 本轮激活的特征
        if (activationResult.activatedTraits.isNotEmpty()) {
            val activeLabels = activationResult.activatedTraits.filter { it.name != "neutral" }
            if (activeLabels.isNotEmpty()) {
                sb.appendLine("本轮用户话题相关，可自然体现的性格：${activeLabels.joinToString("、") { it.label }}")
                // 注入表现方式（引导 LLM 如何自然表达，而非触发字面动作）
                activeLabels.take(2).forEach { trait ->
                    trait.expression.take(2).forEach { expr ->
                        sb.appendLine("  - $expr")
                    }
                }
            }
        }

        // 本轮抑制的特征（重点：防止无关话题带出标志词）
        if (activationResult.suppressedTraits.isNotEmpty()) {
            val suppressedLabels = activationResult.suppressedTraits.map { it.label }
            sb.appendLine("本轮用户话题与以下性格无关，不要主动表现（尤其不要使用相关标志性词汇）：${suppressedLabels.joinToString("、")}")
        }

        // 标志性词汇预算
        val restrictedMarkers = activationResult.markerBudgetMultipliers
            .filter { it.value <= 0f }
            .keys
        if (restrictedMarkers.isNotEmpty()) {
            sb.appendLine("本轮对话中【完全禁止】使用以下标志性词汇：${restrictedMarkers.joinToString("、")}")
        }
        val limitedMarkers = activationResult.markerBudgetMultipliers
            .filterValues { it > 0f }
            .keys
        if (limitedMarkers.isNotEmpty()) {
            sb.appendLine("本轮对话中，以下标志性词汇最多只能使用 1 次（只在话题自然相关时点缀，不要反复用）：${limitedMarkers.joinToString("、")}")
        }

        // 表达等级
        sb.appendLine("人格表现分级（L0-L3）：默认 L1。仅当用户话题与你的核心性格高度相关时才到 L2-L3，其余情况保持自然随和即可，不要刻意演绎单一性格标签。")

        sb.appendLine()
    }

    /**
     * 判断 slot 是否为当前时段
     *
     * 时间解析容错：支持 "24:00" 边界（视为当天 23:59:59 结束，不跨午夜）
     */
    private fun isCurrentSlot(slot: DailySlot): Boolean {
        return try {
            val now = java.time.LocalTime.now(ZoneId.of("Asia/Shanghai"))
            val start = parseTimeSafe(slot.start)
            val end = parseTimeSafe(slot.end)
            if (start <= end) {
                now >= start && now < end
            } else {
                // 跨午夜：now 在 [start, 24:00) 或 [00:00, end) 内都算当前时段
                now >= start || now < end
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 安全解析时间字符串，处理 "24:00" 边界
     * "24:00" 视为 23:59:59（当天结束，不跨午夜）
     */
    private fun parseTimeSafe(timeStr: String): java.time.LocalTime {
        return if (timeStr == "24:00" || timeStr == "24:00:00") {
            java.time.LocalTime.of(23, 59, 59)
        } else {
            java.time.LocalTime.parse(timeStr)
        }
    }
}
