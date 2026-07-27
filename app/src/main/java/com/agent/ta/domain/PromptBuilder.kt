package com.agent.ta.domain

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
 * - 历史消息时间戳：在 ChatInteractor 构造 ChatMessage 时加 [MM-DD HH:MM] 前缀
 */
class PromptBuilder {

    /**
     * 构造完整消息列表
     *
     * @param config Agent 配置
     * @param state 当前状态
     * @param userNickname 用户昵称
     * @param memories 记忆列表
     * @param recentMessages 最近对话历史（ChatMessage 格式，已含 [MM-DD HH:MM] 时间戳前缀）
     * @param isOnboarding 是否为 onboarding 阶段
     * @param currentActivity 当前时段的具体活动（兼容旧调用，优先使用 activityAnchor）
     * @param activityAnchor 当前活动锚点（应用侧权威状态，优先于 currentActivity）
     * @param isInitiate 是否为主动发起
     * @param todaySchedule 今日全天作息
     * @param isPendingCatchup 是否为补回复场景
     * @param isConfigMode 是否为配置模式
     * @param continuousRound 连续对话轮次
     */
    fun build(
        config: AgentConfig,
        state: AgentState,
        userNickname: String,
        memories: List<MemoryEntity>,
        recentMessages: List<ChatMessage>,
        isOnboarding: Boolean = false,
        currentActivity: String? = null,
        activityAnchor: ActivityAnchor? = null,
        isInitiate: Boolean = false,
        todaySchedule: List<DailySlot> = emptyList(),
        isPendingCatchup: Boolean = false,
        isConfigMode: Boolean = false,
        continuousRound: Int = 0,
        observerSnapshots: List<com.agent.ta.infrastructure.observer.ObserverSnapshot> = emptyList()
    ): List<ChatMessage> {
        val consecutiveInboundCount = if (isPendingCatchup) 1 else countTrailingInbound(recentMessages)
        val systemPrompt = buildSystemPrompt(
            config, state, userNickname, memories, isOnboarding,
            currentActivity, activityAnchor, isInitiate, todaySchedule, consecutiveInboundCount,
            isPendingCatchup, isConfigMode, continuousRound, observerSnapshots
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
        isOnboarding: Boolean,
        currentActivity: String?,
        activityAnchor: ActivityAnchor?,
        isInitiate: Boolean,
        todaySchedule: List<DailySlot>,
        consecutiveInboundCount: Int = 1,
        isPendingCatchup: Boolean = false,
        isConfigMode: Boolean = false,
        continuousRound: Int = 0,
        observerSnapshots: List<com.agent.ta.infrastructure.observer.ObserverSnapshot> = emptyList()
    ): String {
        val sb = StringBuilder()

        // ═══════════════════════════════════════════════════════════════════════
        // Zone A: Primacy (开头锚定)
        // LLM 最先看到的内容：时间 + 活动锚点 + 身份核心
        // ═══════════════════════════════════════════════════════════════════════
        buildZoneA(sb, config, state, userNickname, activityAnchor, currentActivity, todaySchedule)

        // ═══════════════════════════════════════════════════════════════════════
        // Zone B: Reference (中间参考)
        // 上下文信息：身份详情 + 记忆 + 作息 + 状态指导 + 观察者数据
        // ═══════════════════════════════════════════════════════════════════════
        buildZoneB(sb, config, state, userNickname, memories, todaySchedule, observerSnapshots)

        // ═══════════════════════════════════════════════════════════════════════
        // Zone C: Recency (结尾锚定)
        // LLM 最后看到的内容：场景 + 一致性规则 + 输出格式 + 时间锚 #2
        // ═══════════════════════════════════════════════════════════════════════
        buildZoneC(
            sb, config, state, userNickname, activityAnchor, currentActivity,
            isInitiate, isPendingCatchup, isConfigMode, isOnboarding,
            consecutiveInboundCount, continuousRound, todaySchedule
        )

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
            val progress = activityAnchor.progressDescription()
            val sourceTag = when (activityAnchor.source) {
                com.agent.ta.domain.anchor.AnchorSource.LLM -> "（你之前设置的）"
                com.agent.ta.domain.anchor.AnchorSource.SCHEDULE -> "（作息表当前时段）"
                com.agent.ta.domain.anchor.AnchorSource.INFERRED -> "（推断）"
            }
            sb.appendLine("活动：${activityAnchor.activity}")
            sb.appendLine("状态：${activityAnchor.state.displayName}")
            sb.appendLine("进度：$progress$sourceTag")
            sb.appendLine("时段：${activityAnchor.slotStart}-${activityAnchor.slotEnd}")
            sb.appendLine("重要：本次回复所有内容必须围绕「${activityAnchor.activity}」这一活动展开，禁止提到其他活动。")
        } else if (!currentActivity.isNullOrBlank()) {
            // 兼容：无 anchor 时用 currentActivity
            val progressDesc = computeActivityProgress(todaySchedule)
            sb.appendLine("活动：$currentActivity")
            if (progressDesc != null) sb.appendLine("进度：$progressDesc")
            sb.appendLine("状态：${state.displayName}")
            sb.appendLine("重要：本次回复所有内容必须围绕「$currentActivity」这一活动展开，禁止提到其他活动。")
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
        observerSnapshots: List<com.agent.ta.infrastructure.observer.ObserverSnapshot> = emptyList()
    ) {
        sb.appendLine("═══ Zone B: 背景参考（上下文信息）═══")
        sb.appendLine()

        val persona = config.agent.persona
        val identity = config.identity
        val hasIdentity = identity.worldSetting.isNotBlank() || identity.personalityCore.isNotBlank()

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

        // 口头禅、自称、称呼
        if (persona.catchphrases.isNotEmpty()) {
            sb.appendLine("口头禅（自然融入对话，不要每句都加）：${persona.catchphrases.joinToString(" / ")}")
        }
        if (persona.selfNickname.isNotBlank()) {
            sb.appendLine("你的自称：「${persona.selfNickname}」")
        }
        if (persona.nicknameForUser.isNotBlank()) {
            sb.appendLine("你对用户的称呼：「${persona.nicknameForUser}」（也可以配合用户给的昵称「${userNickname}」使用）")
        } else {
            sb.appendLine("和你聊天的用户叫「${userNickname}」。")
        }
        sb.appendLine()

        // 兴趣话题
        if (persona.interests.isNotEmpty()) {
            sb.appendLine("你感兴趣的话题（对话中可以自然引入）：${persona.interests.joinToString("、")}")
            sb.appendLine()
        }

        // 禁忌话题
        if (persona.taboos.isNotEmpty()) {
            sb.appendLine("【禁忌话题】以下话题绝对不要聊，用户提起时请婉转避开或保持沉默：")
            persona.taboos.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        // 关系阶段提示
        if (persona.conversationStageHints.isNotEmpty()) {
            sb.appendLine("【关系阶段提示】根据你和用户的熟悉程度调整亲密度：")
            persona.conversationStageHints.forEach { (stage, hint) ->
                sb.appendLine("- $stage：$hint")
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
            sb.appendLine("根据当前时段的进度组织回复：刚开始可以表达「准备做/正要开始」，进行中表达「正在做」，快结束表达「快做完了/马上结束」。")
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

    private fun buildZoneC(
        sb: StringBuilder,
        config: AgentConfig,
        state: AgentState,
        userNickname: String,
        activityAnchor: ActivityAnchor?,
        currentActivity: String?,
        isInitiate: Boolean,
        isPendingCatchup: Boolean,
        isConfigMode: Boolean,
        isOnboarding: Boolean,
        consecutiveInboundCount: Int,
        continuousRound: Int,
        todaySchedule: List<DailySlot>
    ) {
        sb.appendLine("═══ Zone C: 回复规则与输出格式（生成前最后确认）═══")
        sb.appendLine()

        // 当前场景
        buildSceneGuidance(sb, state, activityAnchor, currentActivity, isInitiate, isPendingCatchup, consecutiveInboundCount, continuousRound)

        // 回复逻辑一致性约束（核心规则，放在 Zone C 让 LLM 生成前最后看到）
        buildConsistencyRules(sb, activityAnchor, currentActivity)

        // 作息自主调整能力
        buildScheduleAdjustmentRules(sb)

        // Emoji 表情能力
        buildEmojiRules(sb)

        // 输出格式
        buildOutputFormat(sb, isPendingCatchup, consecutiveInboundCount, isConfigMode, config)

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

        // 时间锚 #2（双时间锚定的第二个，放在最后让 LLM 生成前再次确认时间）
        val nowEnd = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
            .truncatedTo(ChronoUnit.MINUTES)
            .format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"))
        sb.appendLine()
        sb.appendLine("【再次确认：当前时间 $nowEnd】生成回复前请基于此时间判断活动进度。")

        // Onboarding 特殊指令
        if (isOnboarding) {
            sb.appendLine()
            sb.appendLine("这是初次认识阶段，请主动引导对话：")
            sb.appendLine("- 友好地自我介绍")
            sb.appendLine("- 问用户的称呼、职业、兴趣爱好")
            sb.appendLine("- 每次只问一个问题，自然地展开对话")
            sb.appendLine("- 把用户回答的信息输出到 memoryUpdates 中")
        }

        // 配置模式
        if (isConfigMode) {
            buildConfigModeRules(sb, config, userNickname)
        }
    }

    /**
     * 当前场景引导（回复/主动发起/补回复）
     */
    private fun buildSceneGuidance(
        sb: StringBuilder,
        state: AgentState,
        activityAnchor: ActivityAnchor?,
        currentActivity: String?,
        isInitiate: Boolean,
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
            sb.appendLine("   - 当前活动是「$effectiveActivity」，本次回复所有内容必须围绕这一个活动展开")
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
        sb.appendLine("   - 下一个活动必须用「接下来要去」「等下要」「快结束了然后去」描述，绝对不能用「去了」这种进行式")
        sb.appendLine("   - 错误示例：当前在健身，却说「我去洗澡了」← 让用户以为现在就在洗澡")
        sb.appendLine("   - 正确示例：当前在健身，说「快结束了，等下去洗澡」← 明确是未来的事")
        sb.appendLine()

        sb.appendLine("4. 前后轮次一致性（跨轮次回复）")
        sb.appendLine("   - 必须检查对话历史中你之前说过的活动状态（对话历史带 [MM-DD HH:MM] 时间戳）")
        sb.appendLine("   - 新回复必须与之前说的保持一致，除非作息表显示时段已切换")
        sb.appendLine("   - 错误示例：上一轮说「去洗澡了」，这一轮说「还有几组结束」← 健身的说法，与洗澡矛盾")
        sb.appendLine("   - 正确做法：如果之前说「去洗澡了」，这一轮要么继续说洗澡相关，要么说「洗完了」")
        sb.appendLine("   - 如果作息表显示时段已切换（如健身→洗澡），才能说「刚洗完澡」或「在洗澡呢」")
        sb.appendLine()

        sb.appendLine("5. 活动状态变更规则")
        sb.appendLine("   - 活动状态变更只能由作息表时段切换驱动（时间到了切换）或你调用 set_activity 工具显式声明")
        sb.appendLine("   - 不能在回复中凭空改变当前活动")
        sb.appendLine("   - 如果用户问「在干嘛」，只能回答当前时段的 activity")
        sb.appendLine("   - 如果当前进度是「快结束」，可以说「快做完了，接下来要去XX」（XX 是作息表下一个时段的活动）")
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
     * 输出格式
     */
    private fun buildOutputFormat(
        sb: StringBuilder,
        isPendingCatchup: Boolean,
        consecutiveInboundCount: Int,
        isConfigMode: Boolean,
        config: AgentConfig
    ) {
        sb.appendLine("请用以下 JSON 格式回复（不要输出其他内容）：")
        sb.appendLine("{")
        sb.appendLine("  \"replies\": [")
        sb.appendLine("    {")
        sb.appendLine("      \"replyText\": \"这条消息的纯对话文本\",")
        sb.appendLine("      \"action\": \"这条消息的旁白/动作（第三人称，描述此刻姿态/场景/小动作，如：在沙发上躺着）。没有动作就留空字符串\",")
        sb.appendLine("      \"directorPrompt\": \"这条消息的导演指令：语气、语速、情绪\",")
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
        sb.appendLine("  ]")
        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("规则：")
        if (isPendingCatchup) {
            sb.appendLine("- replies 数组只输出 1 条（最多 2 条）简短回复，表达「看到你之前发的消息了，现在简单回应」的感觉")
            sb.appendLine("- 不要对每条历史消息逐条对应回复，不要使用 1. 2. 3. 这种序号格式")
        } else if (consecutiveInboundCount > 1) {
            sb.appendLine("- replies 是你要发的消息数组。当前用户连发了 $consecutiveInboundCount 条消息，replies 长度应为 $consecutiveInboundCount 条左右（每条用户消息对应一条回复）")
            sb.appendLine("- 每条 reply 都要明确针对对应用户消息的内容做出回应，不要泛泛而谈")
            sb.appendLine("- 必须用 replies 数组拆分多条回复，禁止把多条回复合并到一条 replyText 里用 1. 2. 3. 序号格式")
        } else {
            sb.appendLine("- replies 是你要发的消息数组。像真人微信聊天一样，根据情境主动拆成 2-3 条短消息连发，比一条长消息更自然")
            sb.appendLine("- 每条 replyText 控制在 15 字以内，短句更像真人聊天，避免长段落")
            sb.appendLine("  ✓ 合理多回复示例（用户问「在干嘛？」）：")
            sb.appendLine("    第1条「刚起来」")
            sb.appendLine("    第2条「准备去洗漱呢」")
            sb.appendLine("    第3条「你起了没？」")
            sb.appendLine("    （短句连发，每条独立表达一个意思，像真人微信）")
            sb.appendLine("  ✓ 合理拆条场景：先回应+再补充 / 先答+再反问 / 先说现状+再关心对方 / 想到什么补充什么")
            sb.appendLine("  ✗ 禁止两条表达相同意思或重复同一话题（如「宝宝喊我干什么呀」+「宝宝怎么了，在叫我吗」）")
            sb.appendLine("  ✗ 禁止把一句话从中间断开伪装成多条")
            sb.appendLine("  ✗ 禁止单条超过 30 字，长消息必须拆分")
        }
        sb.appendLine("- 单条长度看心情：3 个字或 3 行都行，符合你此刻的状态，但优先短句")
        sb.appendLine("- replyText 是纯对话文本，只包含要说的话本身。绝对不要在 replyText 里写括号、动作描述、emoji 解释或任何非对话内容")
        sb.appendLine("- replyText 中绝对禁止使用 1. 2. 3. 这类数字序号列表格式。如果你想发多条消息，请用 replies 数组拆分，不要在单条 replyText 里列序号")
        sb.appendLine("  正确示例：replyText=\"我现在躺着呢\" action=\"在沙发上躺着\"")
        sb.appendLine("  错误示例：replyText=\"（在沙发上躺着）我现在躺着呢\" ← 不要这样写")
        sb.appendLine("  错误示例：replyText=\"在沙发上躺着，我现在躺着呢\" ← 不要这样写")
        sb.appendLine("- action 是第三人称视角的动作/场景旁白，不会进入语音合成，只在 UI 以浅色斜体文字展示。只在自然适合描述动作时才写，不需要每条都硬凑")
        sb.appendLine("  示例：在沙发上躺着 / 边吃苹果边打字 / 趴在桌子上 / 翻了个身 / 抱着抱枕 / 蹲在椅子上 / 摸了摸头发 / 看了一眼窗外 / 抿了一口水")
        sb.appendLine("  如果当前只是普通打字回复、没有特别的动作场景，就留空字符串")
        sb.appendLine("- directorPrompt 用于每条消息的语音合成，描述这句话应该怎么说话")
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
     * 判断 slot 是否为当前时段
     */
    private fun isCurrentSlot(slot: DailySlot): Boolean {
        return try {
            val now = java.time.LocalTime.now(ZoneId.of("Asia/Shanghai"))
            val start = java.time.LocalTime.parse(slot.start)
            val end = java.time.LocalTime.parse(slot.end)
            if (start <= end) {
                now in start..end
            } else {
                now >= start || now < end
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 计算当前时段的进度描述
     */
    private fun computeActivityProgress(schedule: List<DailySlot>): String? {
        return try {
            val currentSlot = schedule.firstOrNull { isCurrentSlot(it) } ?: return null
            val now = java.time.LocalTime.now(ZoneId.of("Asia/Shanghai"))
            val start = java.time.LocalTime.parse(currentSlot.start)
            val end = if (currentSlot.end == "24:00") java.time.LocalTime.of(23, 59, 59)
                      else java.time.LocalTime.parse(currentSlot.end)

            val totalMinutes = if (start <= end) {
                java.time.Duration.between(start, end).toMinutes()
            } else {
                java.time.Duration.between(start, end).toMinutes().let { if (it < 0) it + 24 * 60 else it }
            }
            val elapsedMinutes = if (start <= end) {
                java.time.Duration.between(start, now).toMinutes()
            } else {
                if (now >= start) java.time.Duration.between(start, now).toMinutes()
                else java.time.Duration.between(java.time.LocalTime.MIDNIGHT, now).toMinutes()
            }
            val remainingMinutes = totalMinutes - elapsedMinutes

            if (totalMinutes <= 0 || elapsedMinutes < 0) return null

            val progress = elapsedMinutes.toFloat() / totalMinutes.toFloat()

            when {
                progress < 0.2f -> "刚开始 ${elapsedMinutes}分钟"
                progress < 0.8f -> "已进行 ${elapsedMinutes}分钟"
                else -> "快结束了，还剩 ${remainingMinutes}分钟"
            }
        } catch (e: Exception) {
            null
        }
    }
}
