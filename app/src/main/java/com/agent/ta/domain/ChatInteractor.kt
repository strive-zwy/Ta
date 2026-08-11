package com.agent.ta.domain

import android.content.Context
import android.util.Log
import com.agent.ta.data.local.entity.ChatMessageEntity
import com.agent.ta.data.local.entity.CommitmentEntity
import com.agent.ta.data.local.entity.FutureEventEntity
import com.agent.ta.data.local.entity.MemoryEntity
import com.agent.ta.data.model.AgentState
import com.agent.ta.data.remote.LlmClient.ToolCallResponse
import com.agent.ta.data.remote.dto.ChatMessage
import com.agent.ta.data.remote.dto.ReplyItem
import com.agent.ta.di.ServiceLocator
import com.agent.ta.domain.consistency.ReplyConsistencyValidator
import com.agent.ta.domain.tool.ToolContext
import com.agent.ta.service.CommitmentScheduler
import com.agent.ta.service.NotificationHelper
import com.agent.ta.util.SystemTtsSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 聊天业务编排
 *
 * 流程：
 * 1. 用户发消息 → 入库
 * 2. 判定当前状态
 * 3. 可以回复 → 等延迟 → 调 LLM → 调 TTS → 入库 → 通知
 * 4. 不可回复 → 入待回复队列 → 状态切换后批量回复
 *
 * 多条消息取消机制：
 * - Agent 一次生成多条回复时，若用户中途发新消息，剩余未发条目会被取消
 * - 通过 companion object 的 currentReplyJob 跨实例共享
 */
class ChatInteractor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val promptBuilder = PromptBuilder()
    private val llmClient = ServiceLocator.llmClient
    private val ttsClient = ServiceLocator.ttsClient
    private val toolRegistry = ServiceLocator.toolRegistry
    private val chatDao = ServiceLocator.chatMessageDao
    private val memoryDao = ServiceLocator.memoryDao
    private val memoryStore = ServiceLocator.memoryStore
    private val observerRegistry = ServiceLocator.observerRegistry
    private val conversationSummarizer = ServiceLocator.conversationSummarizer
    private val futureEventDao = ServiceLocator.futureEventDao
    private val dailyStateDao = ServiceLocator.dailyStateDao
    private val commitmentDao = ServiceLocator.commitmentDao
    private val agentConfigDao = ServiceLocator.agentConfigDao
    private val prefs = ServiceLocator.userPreferences
    private val configProvider = ServiceLocator.agentConfigProvider
    private val agentConfigEditor = ServiceLocator.agentConfigEditor
    private val notificationHelper = NotificationHelper(context)
    private val scheduleAdjuster = ScheduleAdjuster()
    private val systemTtsSynthesizer = SystemTtsSynthesizer(context)
    private val consistencyValidator = ReplyConsistencyValidator()
    private val relationshipService = RelationshipService()
    private val emotionalService = EmotionalService()
    private val activeAgentManager = ServiceLocator.activeAgentManager
    private val firstMeetingCoordinator = ServiceLocator.firstMeetingCoordinator
    private val nicknameResolver = com.agent.ta.domain.firstmeeting.NicknameResolver

    /**
     * 按消息所属 agentId 解析 Agent 名称，用于通知标题。
     * 不使用当前 active Agent 覆盖，防止切换期间通知张冠李戴。
     */
    private suspend fun resolveAgentName(agentId: Long): String {
        return ServiceLocator.agentConfigDao.getById(agentId)?.agentName?.ifBlank { "小雅" } ?: "小雅"
    }

    private fun ensureOperationCurrent(operationContext: AgentOperationContext) {
        AgentGenerationRegistry.shared.requireCurrent(operationContext)
    }

    /**
     * Phase 1 分级睡眠：深睡惊醒冷却机制
     * - lastWakeTime：上次惊醒时间戳
     * - 冷却 10 分钟（WAKE_COOLDOWN_MS = 600000），防止反复惊醒
     */
    @Volatile
    private var lastWakeTime: Long = 0L

    /**
     * 用户发送消息
     *
     * 取消机制：若 Agent 正在生成/发送多条回复，用户发新消息时会取消剩余未发条目，
     * 直接按用户新消息生成回复（不再继续发旧上下文的剩余条目）。
     */
    fun sendUserMessage(text: String) {
        // 0. 拦截斜杠命令（/config /done /help）
        val trimmed = text.trim()
        when {
            trimmed == "/config" || trimmed == "/配置" -> {
                enterConfigMode()
                return
            }
            trimmed == "/done" || trimmed == "/完成" -> {
                finishConfigCollection()
                return
            }
            trimmed == "/help" || trimmed == "/帮助" -> {
                showCommandHelp()
                return
            }
        }

        if (_configMode.value && handleConfigModeSelection(trimmed, true)) {
            return
        }
        if (_configMode.value) {
            handleConfigConversationMessage(trimmed)
            return
        }

        // 1. 取消上一个进行中的回复任务（包括延迟阶段，防止快速连发产生多个并行任务）
        currentReplyJobRef.get()?.cancel()
        currentReplyJobRef.set(null)

        // 2. 立即启动可取消的回复任务（包括入库、延迟、生成回复，都在同一个 Job 中）
        val job = scope.launch {
            // === 多 Agent 隔离 ===
            // 在请求开始时捕获 agentId，整个回复流程（含 LLM 异步调用）只写回该 Agent
            val agentId = activeAgentManager.getRequiredActiveAgentId()
            val operationContext = AgentGenerationRegistry.shared.capture(agentId)

            // === 连续对话节奏优化 ===
            // 检测：Agent 回复后 90 秒内用户继续发消息 → 连续对话
            // 连续对话时：busy 状态也用短延迟（3-8 秒），消息标记已读，立即显示"正在输入中"
            // 非连续对话：busy 状态用完整延迟，消息标记 pending（未读），延迟期间不显示"正在输入中"
            val now = System.currentTimeMillis()
            val isContinuousChat = lastReplyTime > 0 && (now - lastReplyTime) < CONTINUOUS_CHAT_THRESHOLD_MS
            if (isContinuousChat) {
                continuousRound++
                Log.d(TAG, "连续对话第 ${continuousRound} 轮（距上次回复 ${now - lastReplyTime}ms）")
            } else {
                continuousRound = 0
            }

            // 3. 用户消息入库
            val state = com.agent.ta.service.AgentEngine.currentState.value
            // busy 长延迟场景：非连续对话的 busy 状态
            val isBusyLongDelay = state == AgentState.BUSY && !isContinuousChat
            // 获取当前活动锚点，判断是否腾得出手回复
            val activityAnchor = com.agent.ta.service.AgentEngine.getCurrentActivityAnchor()
            val isActivityNonReplyable = activityAnchor != null && !activityAnchor.replyable
            // 消息状态语义：
            // - "pending" 用于 Agent 无法立即回复的场景（UNAVAILABLE 或 活动不可回复如打球/洗澡）
            //   等状态切换或活动结束后由 processPendingReplies 处理
            // - "received" 表示 Agent 已读（即使延迟回复，消息也算已读）
            val initialStatus = when {
                state == AgentState.UNAVAILABLE -> "pending"
                state == AgentState.LIGHT_SLEEP -> "pending"
                isActivityNonReplyable -> "pending"  // 打球/健身/洗澡等腾不出手的活动
                isBusyLongDelay -> "pending"  // busy 非连续对话：延迟期间未读，延迟结束后才标记已读
                else -> "received"
            }
            val userMsg = ChatMessageEntity(
                agentId = agentId,
                direction = "inbound",
                text = text,
                audioPath = null,
                directorPrompt = null,
                state = state.id,
                status = initialStatus,
                createdAt = System.currentTimeMillis()
            )
            val msgId = chatDao.insert(userMsg)

            // Phase 3 情感势能：用户发消息时重置静默计时
            try {
                emotionalService.onUserMessageReceived(agentId)
            } catch (e: Exception) {
                Log.w(TAG, "重置静默计时失败（不影响主流程）", e)
            }

            // 4. 判定当前状态
            if (state == AgentState.UNAVAILABLE) {
                // Phase 1 分级睡眠：检查深睡惊醒
                val currentSlot = com.agent.ta.service.AgentEngine.getCurrentSlot()
                val sleepDepth = currentSlot?.sleepDepth

                if (sleepDepth == "deep") {
                    // 深睡：随机概率触发惊醒 + 10 分钟冷却
                    val now = System.currentTimeMillis()
                    val inCooldown = now - lastWakeTime < 10 * 60 * 1000L
                    if (inCooldown) {
                        // 冷却期内：直接标记 pending，不触发惊醒
                        chatDao.updateStatus(agentId, msgId, "pending", null)
                        return@launch
                    }
                    val wakeChance = configProvider.get().behavior.wakeChancePerDeepSleepMessage
                    if (kotlin.random.Random.nextFloat() < wakeChance) {
                        // 惊醒触发：切换到 LIGHT_SLEEP
                        lastWakeTime = now
                        com.agent.ta.service.AgentEngine.switchToLightSleep()
                        chatDao.updateStatus(agentId, msgId, "received", null)
                        // 继续走回复路径（不 return）
                    } else {
                        // 未触发惊醒：标记 pending
                        chatDao.updateStatus(agentId, msgId, "pending", null)
                        return@launch
                    }
                } else {
                    // 深睡（非 sleepDepth=deep）或洗澡等：不可回复，保持 pending
                    chatDao.updateStatus(agentId, msgId, "pending", null)
                    return@launch
                }
            }

            if (state == AgentState.LIGHT_SLEEP) {
                schedulePendingReplies(LIGHT_SLEEP_DELAY_RANGE.random().toLong() * 1000L)
                return@launch
            }

            // 4b. 活动不可回复（打球/健身/洗澡等）
            // 消息已标记 pending，不启动回复 job
            // 等活动结束（时段切换/LLM anchor 过期）后由 processPendingMessages 处理
            if (isActivityNonReplyable) {
                Log.d(TAG, "当前活动「${activityAnchor!!.activity}」无法回复消息，标记 pending 等活动结束处理")
                chatDao.updateStatus(agentId, msgId, "pending", null)
                return@launch
            }

            // 5. 是否立即显示"正在输入中"
            // - 连续对话：立即显示（Agent 正在和用户快速聊）
            // - normal/idle：立即显示
            // - busy 非连续对话：延迟期间不显示，延迟结束后才显示
            if (!isBusyLongDelay) {
                _isReplying.value = true
            }

            try {
                // 6. 等待延迟后生成回复
                // 连续对话用短延迟（3-8秒），非连续对话用状态配置的延迟
                // Phase 2: 非连续对话延迟乘以关系系数（亲密高→延迟短，保留下限 1 秒）
                val relationshipState = relationshipService.getCurrentState(agentId)
                val delaySec = if (isContinuousChat) {
                    CONTINUOUS_DELAY_RANGE.random().toLong()
                } else {
                    resolveTypingDelaySec(state, relationshipState.intimacyScore)
                }
                delay(delaySec * 1000)

                // busy 非连续对话：延迟结束后才显示"正在输入中"
                if (isBusyLongDelay) {
                    _isReplying.value = true
                }

                // === 首次见面场景检测（Task 15）===
                // NOT_STARTED → 尝试 beginGreeting，成功则用 FIRST_MEETING_REPLY（用户先发消息）
                // GREETING_IN_PROGRESS → 问候被取消（用户发新消息），合并为 FIRST_MEETING_REPLY
                // WAITING_NICKNAME / FOLLOW_UP_ASKED → NORMAL（awaitingNickname 在 generateAgentReply 内检测）
                // 其他 → NORMAL
                var sendScene = ConversationScene.NORMAL
                try {
                    val fmPhase = firstMeetingCoordinator.getPhase(agentId)
                    when (fmPhase) {
                        com.agent.ta.domain.firstmeeting.FirstMeetingPhase.NOT_STARTED -> {
                            if (firstMeetingCoordinator.beginGreeting(agentId)) {
                                sendScene = ConversationScene.FIRST_MEETING_REPLY
                                Log.d(TAG, "用户先发消息 + 首次见面 NOT_STARTED → FIRST_MEETING_REPLY")
                            }
                        }
                        com.agent.ta.domain.firstmeeting.FirstMeetingPhase.GREETING_IN_PROGRESS -> {
                            sendScene = ConversationScene.FIRST_MEETING_REPLY
                            Log.d(TAG, "问候生成中被用户消息打断 → 合并为 FIRST_MEETING_REPLY")
                        }
                        else -> {}
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "首次见面场景检测失败，走 NORMAL", e)
                }

                // 传入连续对话轮次，用于 prompt 提示
                val replied = generateAgentReply(
                    agentId = agentId,
                    operationContext = operationContext,
                    isConfigMode = configMode.value,
                    continuousRound = continuousRound,
                    scene = sendScene
                )
                if (replied && isBusyLongDelay) {
                    chatDao.updateStatus(agentId, msgId, "received", System.currentTimeMillis())
                }
            } finally {
                _isReplying.value = false
            }
        }
        currentReplyJobRef.set(job)
    }

    /**
     * 进入配置模式（用户输入 /config 触发）
     *
     * Agent 切换为"配置助手"身份，引导用户通过对话调整配置。
     * 配置模式下用户消息仍走 LLM 回复，但 PromptBuilder 会注入配置模式分支。
     */
    private fun enterConfigMode() {
        _configMode.value = true
        currentReplyJobRef.get()?.cancel()
        currentReplyJobRef.set(scope.launch {
            val agentId = activeAgentManager.getRequiredActiveAgentId()
            val operationContext = AgentGenerationRegistry.shared.capture(agentId)
            ServiceLocator.configSessionManager.start(agentId, configProvider.get())
            val state = com.agent.ta.service.AgentEngine.currentState.value
            val msg = ChatMessageEntity(
                agentId = agentId,
                direction = "outbound",
                text = com.agent.ta.ui.screens.chat.ConfigQuickReplyPolicy.ENTRY_MESSAGE,
                audioPath = null,
                directorPrompt = null,
                state = state.id,
                status = "sent",
                createdAt = System.currentTimeMillis()
            )
            chatDao.insert(msg)
            notificationHelper.notifyAgentMessage(msg.text ?: "", null, resolveAgentName(agentId))
        })
    }

    private fun handleConfigModeSelection(text: String, allowAliases: Boolean = false): Boolean {
        val action = if (allowAliases) com.agent.ta.ui.screens.chat.ConfigQuickReplyPolicy.matchAction(text) else null
        val mode = when {
            text == "对话式沟通自定义" || action == com.agent.ta.ui.screens.chat.ConfigQuickReplyAction.CUSTOM -> com.agent.ta.domain.config.ConfigSessionMode.CUSTOM_CONVERSATION
            text == "偶像参考（偶像克隆）" || action == com.agent.ta.ui.screens.chat.ConfigQuickReplyAction.CELEBRITY -> com.agent.ta.domain.config.ConfigSessionMode.CELEBRITY_REFERENCE
            text == "动画或动漫人物参考" || action == com.agent.ta.ui.screens.chat.ConfigQuickReplyAction.FICTIONAL -> com.agent.ta.domain.config.ConfigSessionMode.FICTIONAL_CHARACTER_REFERENCE
            else -> return false
        }
        currentReplyJobRef.get()?.cancel()
        currentReplyJobRef.set(scope.launch {
            val agentId = activeAgentManager.getRequiredActiveAgentId()
            val state = com.agent.ta.service.AgentEngine.currentState.value
            chatDao.insert(
                ChatMessageEntity(
                    agentId = agentId,
                    direction = "inbound",
                    text = text,
                    audioPath = null,
                    directorPrompt = null,
                    state = state.id,
                    status = "received",
                    createdAt = System.currentTimeMillis()
                )
            )
            ServiceLocator.configSessionManager.selectMode(agentId, mode)
            val prompt = when (mode) {
                com.agent.ta.domain.config.ConfigSessionMode.CUSTOM_CONVERSATION ->
                    "好，我们通过聊天一步步创建。先告诉我，你希望这个 Agent 是什么样的人，以及你希望你们是什么关系？"
                com.agent.ta.domain.config.ConfigSessionMode.CELEBRITY_REFERENCE ->
                    "好，请告诉我你想参考哪位偶像。可以同时补充职业或代表作品，方便我确认人物并搜索公开资料。"
                com.agent.ta.domain.config.ConfigSessionMode.FICTIONAL_CHARACTER_REFERENCE ->
                    "好，请告诉我角色名称和作品名称，比如“砂金，《崩坏：星穹铁道》”。我会先搜索并整理角色资料。"
                else -> return@launch
            }
            val assistantMessage = ChatMessageEntity(
                agentId = agentId,
                direction = "outbound",
                text = prompt,
                audioPath = null,
                directorPrompt = null,
                state = state.id,
                status = "sent",
                createdAt = System.currentTimeMillis()
            )
            chatDao.insert(assistantMessage)
            notificationHelper.notifyAgentMessage(prompt, null, resolveAgentName(agentId))
        })
        return true
    }

    private fun finishConfigCollection() {
        currentReplyJobRef.get()?.cancel()
        currentReplyJobRef.set(scope.launch {
            val agentId = activeAgentManager.getRequiredActiveAgentId()
            val session = ServiceLocator.configSessionManager.get(agentId)
            if (session == null) {
                insertConfigAssistantMessage(agentId, "现在不在配置模式。输入 /config 可以开始配置。")
                return@launch
            }
            ServiceLocator.configSessionManager.setStage(agentId, com.agent.ta.domain.config.ConfigSessionStage.REVIEWING)
            insertConfigAssistantMessage(
                agentId,
                buildConfigPreview(session.draftConfig, session.referenceName, session.referenceWork)
            )
        })
    }

    private fun handleConfigConversationMessage(text: String) {
        currentReplyJobRef.get()?.cancel()
        currentReplyJobRef.set(scope.launch {
            val agentId = activeAgentManager.getRequiredActiveAgentId()
            val state = com.agent.ta.service.AgentEngine.currentState.value
            chatDao.insert(
                ChatMessageEntity(
                    agentId = agentId,
                    direction = "inbound",
                    text = text,
                    audioPath = null,
                    directorPrompt = null,
                    state = state.id,
                    status = "received",
                    createdAt = System.currentTimeMillis()
                )
            )
            val session = ServiceLocator.configSessionManager.get(agentId) ?: return@launch
            when (text) {
                "确认应用" -> applyConfigDraft(agentId)
                "继续修改" -> {
                    ServiceLocator.configSessionManager.setStage(
                        agentId,
                        if (session.mode == com.agent.ta.domain.config.ConfigSessionMode.CUSTOM_CONVERSATION) {
                            com.agent.ta.domain.config.ConfigSessionStage.COLLECTING_CUSTOM
                        } else {
                            com.agent.ta.domain.config.ConfigSessionStage.COLLECTING_REFERENCE
                        }
                    )
                    insertConfigAssistantMessage(agentId, "好，告诉我你想修改哪一项，我会更新草稿。")
                }
                "查看资料来源" -> insertConfigAssistantMessage(
                    agentId,
                    session.researchJson.ifBlank { "当前草稿没有使用联网资料，主要根据你的描述整理。" }
                )
                "重新生成" -> regenerateReferenceDraft(agentId, session)
                else -> when (session.stage) {
                    com.agent.ta.domain.config.ConfigSessionStage.COLLECTING_REFERENCE -> generateReferenceDraft(agentId, session, text)
                    com.agent.ta.domain.config.ConfigSessionStage.REVIEWING -> {
                        ServiceLocator.configSessionManager.setStage(agentId, com.agent.ta.domain.config.ConfigSessionStage.COLLECTING_CUSTOM)
                        generateCustomConfigReply(agentId, text)
                    }
                    else -> generateCustomConfigReply(agentId, text)
                }
            }
        })
    }

    suspend fun restoreConfigMode(agentId: Long) {
        _configMode.value = ServiceLocator.configSessionManager.get(agentId) != null
    }

    suspend fun sendInitialConfigGuideIfNeeded(agentId: Long) {
        if (prefs.configGuideSent) return
        val text = "欢迎来到TA。\n输入/config可以进入配置模式，我会通过对话帮你创建Agent；\n配置完成后输入/done 查看草稿，确认后才会正式保存。\n输入/help可以查看命令。"
        insertConfigAssistantMessage(agentId, text)
        prefs.configGuideSent = true
    }

    private suspend fun generateCustomConfigReply(agentId: Long, userText: String) {
        val session = ServiceLocator.configSessionManager.get(agentId) ?: return
        val draftJson = kotlinx.serialization.json.Json { encodeDefaults = true }
            .encodeToString(com.agent.ta.data.model.AgentConfig.serializer(), session.draftConfig)
        val reply = llmClient.chat(
            listOf(
                ChatMessage(
                    role = "system",
                    content = """你是中立的 Agent 配置助手。根据用户当前这句话更新配置草稿，一次只追问一个最重要的缺失信息。
只输出 JSON，格式为：
{"replies":[{"replyText":"自然简短的引导或确认","action":"","directorPrompt":"","emoji":"","emotion":"neutral"}],"configUpdate":{"name":null,"gender":null,"age":null,"background":null,"personality":null,"speakingStyle":null,"selfNickname":null,"nicknameForUser":null,"relationshipToUser":null,"catchphrases":null,"interests":null,"taboos":null,"summary":"本轮变更摘要"}}
仅填写用户明确表达或可可靠推断的字段，未涉及字段保持 null。不要声称已经正式保存。用户随时可以输入 /done 查看草稿。
当前草稿：$draftJson"""
                ),
                ChatMessage(role = "user", content = userText)
            )
        )
        reply.configUpdate?.let { ServiceLocator.configSessionManager.applyUpdate(agentId, it) }
        val responseText = reply.replies.firstOrNull()?.replyText
            ?: reply.replyText.takeIf { it.isNotBlank() }
            ?: "我已经记下了。你还希望调整她的性格、关系或说话方式吗？"
        insertConfigAssistantMessage(agentId, responseText)
    }

    private suspend fun generateReferenceDraft(
        agentId: Long,
        session: com.agent.ta.domain.config.ConfigSession,
        input: String
    ) {
        val fictional = session.mode == com.agent.ta.domain.config.ConfigSessionMode.FICTIONAL_CHARACTER_REFERENCE
        val parts = input.split('，', ',', '《', '》').map { it.trim() }.filter { it.isNotBlank() }
        val referenceName = parts.firstOrNull().orEmpty()
        val referenceWork = if (fictional) parts.drop(1).firstOrNull().orEmpty() else ""
        if (referenceName.isBlank()) {
            insertConfigAssistantMessage(agentId, if (fictional) "请告诉我角色名称和作品名称。" else "请告诉我偶像姓名。")
            return
        }
        ServiceLocator.configSessionManager.setStage(agentId, com.agent.ta.domain.config.ConfigSessionStage.RESEARCHING)
        insertConfigAssistantMessage(agentId, "正在搜索并整理${if (fictional) "角色" else "人物"}资料，请稍候…")
        try {
            val cloner = CelebrityCloner()
            val result = cloner.generateReference(
                referenceName = referenceName,
                customNickname = referenceName,
                appContext = context,
                fictional = fictional,
                referenceWork = referenceWork
            )
            val draft = cloner.applyToConfig(session.draftConfig, result, referenceName)
            val researchSummary = buildString {
                append("参考对象：").append(referenceName)
                if (referenceWork.isNotBlank()) append("\n来源作品：").append(referenceWork)
                append("\n资料类型：公开搜索资料与模型整理")
            }
            ServiceLocator.configSessionManager.updateReference(
                agentId,
                referenceName,
                referenceWork,
                researchSummary,
                draft
            )
            insertConfigAssistantMessage(agentId, buildConfigPreview(draft, referenceName, referenceWork))
        } catch (e: Exception) {
            ServiceLocator.configSessionManager.setStage(agentId, com.agent.ta.domain.config.ConfigSessionStage.COLLECTING_REFERENCE)
            insertConfigAssistantMessage(agentId, "资料搜索或配置生成失败：${e.message ?: "未知错误"}。你可以补充人物信息后重试。")
        }
    }

    private suspend fun regenerateReferenceDraft(agentId: Long, session: com.agent.ta.domain.config.ConfigSession) {
        if (session.referenceName.isBlank()) {
            insertConfigAssistantMessage(agentId, "还没有参考人物，请先告诉我人物或角色名称。")
            return
        }
        val input = if (session.referenceWork.isBlank()) session.referenceName else "${session.referenceName}，${session.referenceWork}"
        generateReferenceDraft(agentId, session, input)
    }

    private fun buildConfigPreview(config: com.agent.ta.data.model.AgentConfig, referenceName: String = "", referenceWork: String = ""): String {
        val persona = config.agent.persona
        return buildString {
            append("Agent 配置草稿\n")
            append("名称：").append(config.agent.name.ifBlank { "未命名" }).append('\n')
            if (referenceName.isNotBlank()) append("参考对象：").append(referenceName).append('\n')
            if (referenceWork.isNotBlank()) append("来源作品：").append(referenceWork).append('\n')
            append("性格：").append(persona.personality.joinToString(" / ").ifBlank { "待补充" }).append('\n')
            append("表达：").append(persona.speakingStyle.ifBlank { "待补充" }).append('\n')
            append("关系：").append(persona.relationshipToUser.ifBlank { config.identity.relationshipStance.ifBlank { "待补充" } }).append('\n')
            append("兴趣：").append(persona.interests.joinToString(" / ").ifBlank { "待补充" }).append('\n')
            append("状态：尚未应用，请确认或继续修改")
        }
    }

    private suspend fun insertConfigAssistantMessage(agentId: Long, text: String) {
        val state = com.agent.ta.service.AgentEngine.currentState.value
        chatDao.insert(
            ChatMessageEntity(
                agentId = agentId,
                direction = "outbound",
                text = text,
                audioPath = null,
                directorPrompt = null,
                state = state.id,
                status = "sent",
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun applyConfigDraft(agentId: Long) {
        val session = ServiceLocator.configSessionManager.get(agentId) ?: return
        try {
            val saved = agentConfigEditor.updateAgent(agentId) { session.draftConfig }
            if (!saved) error("目标 Agent 不存在")
            com.agent.ta.service.AgentEngine.reloadAfterConfigChanged(context, agentId)
            ServiceLocator.configSessionManager.complete(agentId)
            _configMode.value = false
            insertConfigAssistantMessage(agentId, "配置已经应用，现在可以继续聊天了。")
        } catch (e: Exception) {
            insertConfigAssistantMessage(agentId, "配置暂时没有保存成功，可以重试，草稿不会丢失。")
        }
    }

    /**
     * 显示命令帮助（用户输入 /help 触发）
     */
    private fun showCommandHelp() {
        scope.launch {
            val agentId = activeAgentManager.getRequiredActiveAgentId()
            val state = com.agent.ta.service.AgentEngine.currentState.value
            val msg = ChatMessageEntity(
                agentId = agentId,
                direction = "outbound",
                text = "可用命令：\n/config - 进入 Agent 配置模式\n/done - 退出配置模式\n/help - 显示命令帮助",
                audioPath = null,
                directorPrompt = null,
                state = state.id,
                status = "sent",
                createdAt = System.currentTimeMillis()
            )
            chatDao.insert(msg)
            notificationHelper.notifyAgentMessage(msg.text ?: "", null, resolveAgentName(agentId))
        }
    }

    /**
     * 解析当前状态的"正在输入"显示时长（秒）
     *
     * 优先级：
     * 1. Admin v2 typing_indicator_duration[state.id] = [min, max] → 随机区间
     * 2. AgentEngine.getReplyDelaySec()（基于 replyDelaySec 配置或状态默认）
     * 3. 兜底 1 秒
     */
    private fun resolveTypingDelaySec(state: AgentState, intimacyScore: Int = 0): Long {
        val typingDuration = configProvider.get().behavior.typingIndicatorDuration[state.id]
        val baseDelay = if (typingDuration != null && typingDuration.size >= 2) {
            val min = typingDuration[0].coerceAtLeast(0)
            val max = typingDuration[1].coerceAtLeast(min)
            (min..max).random().toLong().coerceAtLeast(0L)
        } else {
            com.agent.ta.service.AgentEngine.getReplyDelaySec() ?: 1L
        }
        // Phase 2 关系系统：亲密高→延迟短
        // 系数公式：1.2 - intimacy/100 * 0.5（intimacy=0 时 ×1.2、intimacy=100 时 ×0.7）
        // 保留下限 1 秒
        val coefficient = 1.2 - (intimacyScore.coerceIn(0, 100) / 100.0 * 0.5)
        return (baseDelay * coefficient).toLong().coerceAtLeast(1L)
    }

    /**
     * 处理待回复消息（状态切换后调用）
     *
     * 与正常用户消息回复不同：
     * - 不走"连发对应回复"路径（避免 LLM 对每条 pending 消息都给独立 reply，导致一次性吐 5 条带序号的消息）
     * - 把所有 pending 消息合并成一条"补充说明"喂给 LLM，让 Agent 用当前状态自然简短回应
     * - 明确告知 LLM：这些消息是之前发的，现在是新状态，按当前状态回复，禁止序号
     * - 处理完后把所有 pending 消息标记为 "received"（避免下次状态切换又被重复触发）
     *
     * 也纳入 currentReplyJob 跟踪，用户发新消息时取消。
     */
    fun processPendingReplies() {
        val delayMs = if (com.agent.ta.service.AgentEngine.currentState.value == AgentState.LIGHT_SLEEP) {
            LIGHT_SLEEP_DELAY_RANGE.random().toLong() * 1000L
        } else {
            0L
        }
        schedulePendingReplies(delayMs, replaceExisting = delayMs == 0L)
    }

    private fun schedulePendingReplies(delayMs: Long, replaceExisting: Boolean = false) {
        if (replaceExisting) pendingReplyJobRef.getAndSet(null)?.cancel()
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var shouldContinue = false
            try {
                if (delayMs > 0L) delay(delayMs)
                shouldContinue = processPendingRepliesNow()
            } finally {
                pendingReplyJobRef.compareAndSet(coroutineContext[Job], null)
            }
            if (shouldContinue) {
                val agentId = activeAgentManager.getRequiredActiveAgentId()
                if (chatDao.getPendingMessages(agentId).isNotEmpty()) {
                    val nextDelayMs = if (com.agent.ta.service.AgentEngine.currentState.value == AgentState.LIGHT_SLEEP) {
                        LIGHT_SLEEP_DELAY_RANGE.random().toLong() * 1000L
                    } else {
                        0L
                    }
                    schedulePendingReplies(nextDelayMs)
                }
            }
        }
        if (pendingReplyJobRef.compareAndSet(null, job)) {
            job.start()
        } else {
            job.cancel()
        }
    }

    private suspend fun processPendingRepliesNow(): Boolean {
        val agentId = activeAgentManager.getRequiredActiveAgentId()
        val operationContext = AgentGenerationRegistry.shared.capture(agentId)
        val state = com.agent.ta.service.AgentEngine.currentState.value
        if (state == AgentState.UNAVAILABLE) return false
        val batchId = UUID.randomUUID().toString()
        val claimedAt = System.currentTimeMillis()
        if (chatDao.claimPending(agentId, batchId, claimedAt) == 0) return false

        // claimPending 成功后，批次内的消息已标记为 processing，必须保证最终 complete 或 release，
        // 否则消息会卡在 processing 状态导致队列阻塞。
        var completed = false
        var batchReleased = false
        try {
            val pending = chatDao.getProcessingBatch(agentId, batchId)
            if (pending.isEmpty()) {
                // claimPending 返回 >0 但 getProcessingBatch 为空（竞态/被其他流程释放），直接释放批次
                chatDao.releaseBatch(agentId, batchId)
                batchReleased = true
                return false
            }

            // 把所有 pending 消息合并成一条"补充说明"用户消息入库
            // 让 LLM 知道用户之前说了什么，但不触发逐条对应回复
            val nowForPending = System.currentTimeMillis()
            val pendingSummary = pending.joinToString("\n") { msg ->
                val content = buildString {
                    if (!msg.emoji.isNullOrBlank()) append(msg.emoji)
                    if (!msg.text.isNullOrBlank()) {
                        if (isNotEmpty()) append(" ")
                        append(msg.text)
                    }
                }
                val timeGap = relativeTimeGap(nowForPending - msg.createdAt)
                "（$timeGap）$content"
            }

            _isReplying.value = true
            completed = generateAgentReply(
                agentId = agentId,
                operationContext = operationContext,
                isPendingCatchup = true,
                pendingContext = pendingSummary
            )
            if (!completed) {
                Log.w(TAG, "补回复生成失败，批次已释放回 pending 队列: batchId=$batchId")
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "补回复任务被取消，释放批次回 pending: batchId=$batchId")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "补回复任务异常，释放批次回 pending: batchId=$batchId", e)
        } finally {
            if (!batchReleased) {
                if (completed) {
                    chatDao.completeBatch(agentId, batchId, System.currentTimeMillis())
                } else {
                    chatDao.releaseBatch(agentId, batchId)
                }
            }
            _isReplying.value = false
        }
        return completed
    }

    /**
     * Agent 主动发起对话（无聊时 / 首次见面问候）
     *
     * v2 集成 ThinkActDecider：
     * - topicHint 由 ThinkActDecider.act() 生成，包含话题方向 + persona 引导
     * - topicHint 为空时退化为 v1 行为（无引导，LLM 即兴发挥）
     *
     * 主动发起的回复任务也纳入 currentReplyJob，
     * 用户发新消息时会取消正在进行的主动发起剩余条目。
     */
    fun agentInitiate(topicHint: String = "") {
        val agentId = activeAgentManager.getRequiredActiveAgentId()
        agentInitiate(agentId, topicHint)
    }

    fun agentInitiate(agentId: Long, topicHint: String = "") {
        val operationContext = AgentGenerationRegistry.shared.capture(agentId)
        currentReplyJobRef.get()?.cancel()
        currentReplyJobRef.set(scope.launch {
            try {
                _isReplying.value = true
                generateAgentReply(agentId = agentId, operationContext = operationContext, isInitiate = true, initiateTopic = topicHint)
            } finally {
                _isReplying.value = false
                currentReplyJobRef.set(null)
            }
        })
    }

    suspend fun agentInitiateAndWait(agentId: Long, topicHint: String = ""): Boolean {
        val operationContext = AgentGenerationRegistry.shared.capture(agentId)
        val startedAt = System.currentTimeMillis()
        return commitmentReplyMutex.withLock {
            ensureOperationCurrent(operationContext)
            try {
                generateAgentReply(
                    agentId = agentId,
                    operationContext = operationContext,
                    isInitiate = true,
                    initiateTopic = topicHint
                )
                ensureOperationCurrent(operationContext)
                chatDao.countOutboundSince(agentId, startedAt) > 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "承诺主动消息生成失败", e)
                false
            }
        }
    }

    /**
     * 触发首次见面问候（Task 12/15）
     *
     * 调用时机：
     * - 默认 Agent 模型配置可用后
     * - 导入 Agent 事务完成后
     *
     * 流程：
     * 1. 检查 FirstMeetingPhase 是否为 NOT_STARTED
     * 2. beginGreeting CAS 抢占（防止并发生成两次问候）
     * 3. 抢占成功 → 以 FIRST_MEETING_GREETING 场景生成回复
     * 4. 抢占失败 → 静默跳过（已有问候在生成或已完成）
     *
     * 用户先发消息时不会走此方法，而是在 sendUserMessage 中合并为 FIRST_MEETING_REPLY。
     */
    fun triggerFirstMeetingGreeting() {
        currentReplyJobRef.get()?.cancel()
        currentReplyJobRef.set(scope.launch {
            try {
                _isReplying.value = true
                val agentId = activeAgentManager.getRequiredActiveAgentId()

                val phase = firstMeetingCoordinator.getPhase(agentId)
                if (phase != com.agent.ta.domain.firstmeeting.FirstMeetingPhase.NOT_STARTED) {
                    Log.d(TAG, "triggerFirstMeetingGreeting: agentId=$agentId phase=$phase，非 NOT_STARTED，跳过")
                    return@launch
                }

                // CAS 抢占：NOT_STARTED → GREETING_IN_PROGRESS
                val grabbed = firstMeetingCoordinator.beginGreeting(agentId)
                if (!grabbed) {
                    Log.d(TAG, "triggerFirstMeetingGreeting: agentId=$agentId 抢占失败（并发竞争），跳过")
                    return@launch
                }

                generateAgentReply(
                    agentId = agentId,
                    isInitiate = true,
                    scene = ConversationScene.FIRST_MEETING_GREETING
                )
            } finally {
                _isReplying.value = false
                currentReplyJobRef.set(null)
            }
        })
    }

    /**
     * 生成 Agent 回复
     *
     * 支持多条消息：LLM 可输出 replies 数组，每条独立合成语音 + 独立入库，
     * 像真人微信连续发几条短消息。
     *
     * @param isPendingCatchup 是否为"补回复 pending 消息"场景。
     *        true 时禁用"连发对应回复"路径，强制单条简短回复，避免一次性吐 N 条带序号的消息
     */
    private suspend fun generateAgentReply(
        agentId: Long,
        operationContext: AgentOperationContext = AgentGenerationRegistry.shared.capture(agentId),
        isInitiate: Boolean = false,
        isPendingCatchup: Boolean = false,
        isConfigMode: Boolean = false,
        continuousRound: Int = 0,
        initiateTopic: String = "",
        scene: ConversationScene = ConversationScene.NORMAL,
        pendingContext: String? = null
    ): Boolean {
        try {
            // 跨天检测：确保作息是今天的（避免 App 跨天运行时基于前一天作息回复）
            com.agent.ta.service.AgentEngine.ensureTodayScheduleFresh(context)

            // 取最近 20 条消息作为上下文
            val recentMessages = chatDao.getAll(agentId).takeLast(20)
            // 构造 ChatMessage：用「相对时间间隔」的自然语言标注，不使用 [MM-dd HH:mm] 时间戳
            // 原因：LLM 看到方括号时间戳格式后会模仿，把多条消息塞进一个 replyText 并用时间戳分隔
            // 改用「3分钟前」「昨天」等自然语言，LLM 不会在回复中模仿这种格式
            val now = System.currentTimeMillis()
            val chatMessages = recentMessages.map { msg ->
                // 纯 emoji 消息 text=null，需要把 emoji 也带上，否则 LLM 看不到
                val rawContent = buildString {
                    if (!msg.emoji.isNullOrBlank()) append(msg.emoji)
                    if (!msg.text.isNullOrBlank()) {
                        if (isNotEmpty()) append(" ")
                        append(msg.text)
                    }
                }
                val timeGap = relativeTimeGap(now - msg.createdAt)
                ChatMessage(
                    role = if (msg.direction == "inbound") "user" else "assistant",
                    content = "（$timeGap）$rawContent"
                )
            }.let { base ->
                // 主动发起场景：在对话历史末尾追加一条 system 锚定消息，
                // 防止 LLM 把历史中最后一条 user 消息当成"刚收到的"而回复"刚看到你的消息"
                // 即使 system prompt 顶部已声明"主动发起"，LLM 的"看到 user 就回复"训练倾向仍可能压不住，
                // 必须在最近位置再锚定一次，并明确告知用户已沉默多久
                if (isInitiate) {
                    val lastInbound = recentMessages.lastOrNull { it.direction == "inbound" }
                    val silenceMinutes = if (lastInbound != null) {
                        ((now - lastInbound.createdAt) / 60_000).toInt().coerceAtLeast(0)
                    } else -1  // -1 表示从未收到过用户消息（首次主动发起）
                    val silenceDesc = when {
                        silenceMinutes < 0 -> "用户从未给你发过消息"
                        silenceMinutes < 60 -> "用户已沉默 ${silenceMinutes} 分钟"
                        else -> "用户已沉默 ${silenceMinutes / 60} 小时 ${(silenceMinutes % 60)} 分钟"
                    }
                    base + ChatMessage(
                        role = "system",
                        content = "【重要·场景锚定】当前是 Agent 主动发起场景。" +
                            "${silenceDesc}，用户没有刚发消息给你，上面的对话历史只是上下文参考。" +
                            "不要回复用户消息，不要说「刚看到你的消息」「收到你的消息」之类，" +
                            "你此刻是主动找话题/分享/碎碎念，不是在回复用户。"
                    )
                } else if (!pendingContext.isNullOrBlank()) {
                    base + ChatMessage(
                        role = "system",
                        content = "【待回复消息】用户之前发了这些消息：\n$pendingContext\n请结合当前状态简短自然地统一回应，不要逐条复述。"
                    )
                } else base
            }

            // 取记忆：v2 三层记忆系统（core_memory 永驻 + memory_items 按需召回）
            val coreMemories = memoryStore.getCoreMemory(agentId)
            val recentMemoryItems = memoryStore.getRecentItems(agentId, 10)
            val memories = (coreMemories + recentMemoryItems).distinctBy { it.id }
            adjustMemoryImportance(agentId, memories)

            // 获取当前活动锚点（应用侧权威状态，优先于 currentActivity）
            val activityAnchor = com.agent.ta.service.AgentEngine.getCurrentActivityAnchor()

            // 收集观察者完整快照（v2 L0 基础设施层，注入 Zone B 让 LLM 看到完整当前状态）
            // 解决 MochiBot "主回复路径错失状态" 的核心问题
            val observerSnapshots = observerRegistry.collectAll()

            // 获取历史对话摘要（v2 L2 认知层，注入 Zone B 节省 Token 保持上下文连贯）
            val currentBucketId = conversationSummarizer.getCurrentBucketId(agentId)
            val priorSummary = conversationSummarizer.getPriorSummaries(agentId, currentBucketId)

            // === 首次见面场景与 awaitingNickname 检测（Task 15）===
            // scene 由调用方传入（sendUserMessage / triggerFirstMeetingGreeting）
            // awaitingNickname 在此方法内根据 FirstMeetingPhase 检测：
            // - WAITING_NICKNAME / FOLLOW_UP_ASKED → true，PromptBuilder 注入 nicknameResolution 引导
            // - 其他 → false
            val effectiveScene = scene
            val awaitingNickname = try {
                firstMeetingCoordinator.isAwaitingNickname(agentId)
            } catch (e: Exception) {
                Log.w(TAG, "检测 awaitingNickname 失败，默认 false", e)
                false
            }

            // 读取 per-agent 称呼（优先 AgentConfig.agent.persona.nicknameForUser，兜底 prefs.userNickname）
            val perAgentNickname = configProvider.get().agent.persona.nicknameForUser
            val effectiveUserNickname = perAgentNickname.ifBlank { prefs.userNickname }

            // 构造 LLM 请求（Zone A/B/C 三段架构 + 双时间锚定 + ActivityAnchor + 观察者数据 + 对话摘要 + 计划 vs 实际对比 + 昨日状态延续 + 今日承诺）
            val planVsActualDiff = com.agent.ta.service.AgentEngine.getPlanVsActualDiff()
            // 查询昨日 DailyState 构造状态延续文本（Step 24）
            val yesterdayCarryOver = buildYesterdayCarryOver(agentId)
            // 查询今日 pending/triggered 承诺（Step 25）
            val carryZone = java.time.ZoneId.of("Asia/Shanghai")
            val carryToday = java.time.LocalDate.now(carryZone)
            val carryStart = carryToday.atStartOfDay(carryZone).toInstant().toEpochMilli()
            val carryEnd = carryToday.plusDays(1).atStartOfDay(carryZone).toInstant().toEpochMilli()
            val todayCommitments = (
                commitmentDao.getByStatus(agentId, "pending") +
                    commitmentDao.getByStatus(agentId, "claimed") +
                    commitmentDao.getByStatus(agentId, "delivered")
                )
                .filter { c ->
                    // 只保留今日相关的承诺：triggerAt 在今天，或 triggerAt 为 null 且今天创建
                    c.triggerAt?.let { it in carryStart until carryEnd }
                        ?: (c.createdAt in carryStart until carryEnd)
                }

            // === Persona Engine 运行时人格系统（模块1-4）===
            // 从当前 Agent 配置派生人格模型 + 分析用户最新消息上下文，
            // 注入 PromptBuilder 动态控制本轮激活/抑制的人格特征与标志词预算
            val currentConfigForPersona = configProvider.get()
            val currentPersonaModel = com.agent.ta.domain.persona.PersonaModelBuilder.build(currentConfigForPersona)
            // 取最近一条用户消息作为上下文分析输入（主动发起/补回复时用最近一条如有）
            val lastUserText = chatMessages.lastOrNull { it.role == "user" }?.content?.replace(Regex("^（[^）]*）"), "")?.trim()
                ?: ""
            val currentContextAnalysis = com.agent.ta.domain.persona.ContextAnalyzer.analyze(lastUserText)

            val llmMessages = promptBuilder.build(
                config = configProvider.get(),
                state = com.agent.ta.service.AgentEngine.currentState.value,
                userNickname = effectiveUserNickname,
                memories = memories,
                recentMessages = chatMessages,
                currentActivity = com.agent.ta.service.AgentEngine.getCurrentActivity(),
                activityAnchor = activityAnchor,
                isInitiate = isInitiate,
                initiateTopic = initiateTopic,
                todaySchedule = com.agent.ta.service.AgentEngine.getTodaySchedule(),
                isPendingCatchup = isPendingCatchup,
                isConfigMode = isConfigMode,
                continuousRound = continuousRound,
                observerSnapshots = observerSnapshots,
                conversationSummary = priorSummary,
                planVsActualDiff = planVsActualDiff,
                yesterdayCarryOver = yesterdayCarryOver,
                todayCommitments = todayCommitments,
                relationshipState = relationshipService.getCurrentState(agentId),
                recentMilestones = relationshipService.getRecentMilestones(agentId, 3),
                emotionalState = emotionalService.getCurrentState(agentId),
                scene = effectiveScene,
                awaitingNickname = awaitingNickname,
                commitmentTimerEnabled = prefs.commitmentTimerEnabled,
                personaModel = currentPersonaModel,
                contextAnalysis = currentContextAnalysis
            )

            // 调 LLM（支持工具调用）+ 一致性校验重试循环
            // 校验失败时追加修正指令重试，最多 MAX_CONSISTENCY_RETRIES 次
            var reply = callLlmWithToolSupport(
                messages = llmMessages,
                isConfigMode = isConfigMode,
                isPendingCatchup = isPendingCatchup
            )
            ensureOperationCurrent(operationContext)
            var consistencyRetryCount = 0
            var currentMessages = llmMessages
            while (consistencyRetryCount < MAX_CONSISTENCY_RETRIES) {
                coroutineContext.ensureActive()
                val validationResult = consistencyValidator.validate(reply, activityAnchor, chatMessages)
                if (validationResult.passed) {
                    if (consistencyRetryCount > 0) {
                        Log.d(TAG, "一致性校验通过（重试 $consistencyRetryCount 次后）")
                    }
                    break
                }
                Log.w(TAG, "一致性校验失败（第 ${consistencyRetryCount + 1} 次），重试中：${validationResult.issues.joinToString("; ")}")
                // 追加修正指令作为 system 消息，让 LLM 修正后重新回复
                currentMessages = currentMessages + ChatMessage(
                    role = "system",
                    content = validationResult.correctionHint
                )
                reply = callLlmWithToolSupport(
                    messages = currentMessages,
                    isConfigMode = isConfigMode,
                    isPendingCatchup = isPendingCatchup
                )
                consistencyRetryCount++
            }
            if (consistencyRetryCount >= MAX_CONSISTENCY_RETRIES) {
                Log.w(TAG, "一致性校验重试达上限($MAX_CONSISTENCY_RETRIES)，使用最后一次回复")
            }

            // === Persona Guard 后置守卫（模块5，决策 B：FLAG 后重新生成一次）===
            // 检测回复是否过度使用角色标志性词汇（如砂金的"赌局/筹码/下注"）。
            // 若 FLAG，则追加"减少标志性表达"指令重新调 LLM 一次；重试仍 FLAG 则放弃（最多重试 1 次）。
            val personaGuardItems = if (reply.replies.isNotEmpty()) {
                reply.replies.filter { it.replyText.isNotBlank() || it.emoji.isNotBlank() }
            } else if (reply.replyText.isNotBlank()) {
                listOf(ReplyItem(replyText = reply.replyText))
            } else emptyList()

            if (personaGuardItems.isNotEmpty() && currentPersonaModel.lexicalMarkers.isNotEmpty()) {
                val guardResult = com.agent.ta.domain.persona.PersonaGuard.check(
                    model = currentPersonaModel,
                    items = personaGuardItems
                )
                if (guardResult.isFlagged) {
                    Log.w(TAG, "PersonaGuard FLAG：${guardResult.reason}，追加降权指令重新生成一次")
                    // 判定哪些标记词超限，构造降权指令
                    val flaggedMarkers = currentPersonaModel.lexicalMarkers
                        .filter { marker -> personaGuardItems.any { it.replyText.contains(marker) } }
                    val markerList = flaggedMarkers.ifEmpty { currentPersonaModel.lexicalMarkers.take(3) }.joinToString("、")
                    val deemphasisHint = "你上一轮回复过度使用标志性表达（${guardResult.reason}）。" +
                        "请保留你的性格和语气，但减少「$markerList」这类比喻和词汇的使用，让表达更自然、更贴近日常对话，不要反复演绎同一主题。"
                    currentMessages = currentMessages + ChatMessage(role = "system", content = deemphasisHint)
                    reply = callLlmWithToolSupport(
                        messages = currentMessages,
                        isConfigMode = isConfigMode,
                        isPendingCatchup = isPendingCatchup
                    )
                    // 重试后不再递归 Guard，避免死循环（最多重试 1 次）
                }
            }

            // === 首次见面元数据校验（Task 12/15）===
            // FIRST_MEETING_GREETING 场景：必须 introducedSelf && askedForNickname
            // 第一次失败 → 追加纠正提示重试一次
            // 第二次仍失败 → 使用最小兜底问句（不再调 LLM）
            if (effectiveScene == ConversationScene.FIRST_MEETING_GREETING) {
                val validator = com.agent.ta.domain.firstmeeting.FirstMeetingValidator
                if (!validator.isMetaValid(reply, requireBothGoals = true)) {
                    Log.w(TAG, "首次问候元数据校验失败，追加纠正提示重试一次")
                    val correctionHint = validator.buildCorrectionHint(reply.firstMeetingMeta)
                    val retryMessages = llmMessages + ChatMessage(role = "system", content = correctionHint)
                    reply = callLlmWithToolSupport(
                        messages = retryMessages,
                        isConfigMode = isConfigMode,
                        isPendingCatchup = isPendingCatchup
                    )
                    if (!validator.isMetaValid(reply, requireBothGoals = true)) {
                        Log.w(TAG, "首次问候重试仍失败，使用最小兜底问句")
                        reply = validator.buildFallbackReply(configProvider.get().agent.name.ifBlank { "小雅" })
                    }
                }
            }

            // === 处理称呼解析（Task 15）===
            // 解析 LLM 输出的 nicknameResolution，写入 AgentConfig 并推进首次见面状态机
            ensureOperationCurrent(operationContext)
            processNicknameResolution(agentId, reply, awaitingNickname)

            // 存记忆（v2 通过 MemoryStore 统一管理，自动分级入库）
            reply.memoryUpdates.forEach { update ->
                ensureOperationCurrent(operationContext)
                memoryStore.addMemory(agentId, update, if (isInitiate) "event" else "chat")
            }

            // 存未来事件（LLM 从对话中提取的）
            if (reply.futureEvents.isNotEmpty()) {
                reply.futureEvents.forEach { event ->
                    ensureOperationCurrent(operationContext)
                    futureEventDao.insert(
                        FutureEventEntity(
                            agentId = agentId,
                            date = event.date,
                            description = event.description,
                            source = "chat"
                        )
                    )
                }
                Log.d(TAG, "已存入 ${reply.futureEvents.size} 条未来事件")
            }

            // 存承诺/约定（LLM 从对话中提取的）
            if (reply.commitments.isNotEmpty()) {
                reply.commitments.forEach { item ->
                    ensureOperationCurrent(operationContext)
                    val triggerAtTs = item.triggerAt?.let { parseIso8601ToTimestamp(it) }
                    val commitment = CommitmentEntity(
                        agentId = agentId,
                        type = item.type,
                        content = item.content,
                        participants = item.participants,
                        triggerAt = triggerAtTs,
                        deadline = null,
                        status = "pending",
                        source = "chat",
                        relatedMessageId = null
                    )
                    val id = ServiceLocator.commitmentDao.insert(commitment)
                    // 注册 AlarmManager 触发
                    if (triggerAtTs != null && triggerAtTs > System.currentTimeMillis()) {
                        CommitmentScheduler(context).scheduleCommitmentTrigger(commitment.copy(id = id))
                    }
                }
                Log.d(TAG, "已存储 ${reply.commitments.size} 条承诺")
            }

            // 处理承诺完成/取消（LLM 从对话中识别的）
            if (reply.commitmentUpdates.isNotEmpty()) {
                reply.commitmentUpdates.forEach { update ->
                    ensureOperationCurrent(operationContext)
                    // 按 content 关键词匹配 pending/triggered 状态的承诺
                    val candidates =
                        ServiceLocator.commitmentDao.getByStatus(agentId, "pending") +
                            ServiceLocator.commitmentDao.getByStatus(agentId, "claimed") +
                            ServiceLocator.commitmentDao.getByStatus(agentId, "delivered")
                    val matched = candidates.find {
                        it.content.contains(update.content) || update.content.contains(it.content)
                    }
                    matched?.let {
                        ServiceLocator.commitmentDao.updateStatus(agentId, it.id, update.status)
                        if (update.status == "completed" || update.status == "cancelled") {
                            CommitmentScheduler(context).cancelCommitmentTrigger(agentId, it.id)
                        }
                        Log.d(TAG, "承诺状态更新：${it.content} → ${update.status}")
                    }
                }
            }

            // 处理 Agent 自主作息调整（v3 事件驱动）
            // LLM 输出 scheduleAdjustment（含 adjustmentType 和参数），ScheduleAdjuster 局部修改 slots
            // 不再调 LLM 重新生成全天作息，省一次调用 + 保留已完成时段
            if (reply.scheduleAdjustment.shouldAdjust) {
                ensureOperationCurrent(operationContext)
                val config = configProvider.get()
                val currentSlots = com.agent.ta.service.AgentEngine.getTodaySchedule()
                Log.d(TAG, "Agent 决定调整作息（${reply.scheduleAdjustment.adjustmentType}）：${reply.scheduleAdjustment.reason}")
                val newSlots = scheduleAdjuster.applyAdjustment(
                    config,
                    reply.scheduleAdjustment,
                    currentSlots
                )
                if (newSlots.isNotEmpty() && newSlots != currentSlots) {
                    // 更新状态机 + 重新注册调度
                    com.agent.ta.service.AgentEngine.updateSchedule(newSlots)
                    Log.d(TAG, "作息已调整并更新状态机")
                }
            }

            // 处理配置变更（配置模式下 LLM 输出 configUpdate）
            if (isConfigMode && reply.configUpdate != null) {
                ensureOperationCurrent(operationContext)
                applyConfigUpdate(agentId, reply.configUpdate)
            }

            // 展开回复列表：优先 replies，否则 fallback 到单条 replyText
            // 保留 emoji 消息（replyText 为空但有 emoji）
            val items = if (reply.replies.isNotEmpty()) {
                reply.replies.filter { it.replyText.isNotBlank() || it.emoji.isNotBlank() }
            } else if (reply.replyText.isNotBlank()) {
                listOf(ReplyItem(
                    replyText = reply.replyText,
                    action = reply.action,
                    directorPrompt = reply.directorPrompt
                ))
            } else emptyList()

            if (items.isEmpty()) {
                Log.w(TAG, "LLM 未返回任何回复内容")
                // 首次问候 LLM 无内容：回退状态机（Task 15）
                if (effectiveScene == ConversationScene.FIRST_MEETING_GREETING) {
                    try { firstMeetingCoordinator.onGreetingLlmFailure(agentId) } catch (e: Exception) {
                        Log.w(TAG, "onGreetingLlmFailure 失败（空回复）", e)
                    }
                }
                return false
            }

            Log.d(TAG, "本次回复 ${items.size} 条消息")

            // 防御性清理：从每条 replyText 中提取括号动作到 action
            // （防御 LLM 把动作写进 replyText 被读成语音）
            coroutineContext.ensureActive()  // 被取消时抛 CancellationException
            ensureOperationCurrent(operationContext)
            val cleanedItems = items.map { item ->
                val cleanedText = item.replyText.replace(BRACKET_REGEX, "").replace(Regex("\\s+"), " ").trim()
                val extractedAction = BRACKET_REGEX.findAll(item.replyText)
                    .map { it.groupValues[1].trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("、")
                    .ifBlank { null }
                val finalAction = item.action.ifBlank { extractedAction ?: "" }
                item.copy(replyText = cleanedText, action = finalAction)
            }

            // 防御性拆分：单条 replyText 含多个完整句子时按句末标点拆成多条消息
            // （LLM 偶发把多句塞进一条，这里作为最后一道防线，每条都是完整句子）
            // action/directorPrompt 只保留在拆分后的第一条，避免重复入库
            val splitItems = cleanedItems.flatMap(TtsTextPolicy::splitLongReply)

            // 判断是否走"多消息独立入库"路径：
            // 条件：有意义的 replyText（长度 >= 2）至少有 2 条
            // 每条独立入库显示（像真人微信连发），只对合并文本做一次 TTS
            // 多条回复的连贯性和数量完全由 PromptBuilder 引导 LLM 生成（见 system prompt），
            // 代码层不做过滤，让 LLM 像真人一样自主决定发几条
            val deliveryItems = ReplyDeliveryPolicy.attachPureEmoji(splitItems)
            val effectiveReplies = deliveryItems.filter { it.replyText.length >= 2 }

            val useMultiMessageMode = effectiveReplies.size >= 2

            if (useMultiMessageMode) {
                Log.d(TAG, "多消息逐条合成并入库：${deliveryItems.size} 条")
                val persisted = persistMultipleReplies(agentId, operationContext, deliveryItems)
                if (!persisted) return false
                // 首次见面状态推进（Task 15）
                advanceFirstMeetingState(agentId, effectiveScene)
                lastReplyTime = System.currentTimeMillis()
                return true
            }

            // 否则走原合并逻辑兜底（处理 LLM 异常输出、纯 emoji、单条 reply 等场景）
            val mergedReplyText = deliveryItems.joinToString("\n") { it.replyText }
            val mergedAction = deliveryItems.firstOrNull { it.action.isNotBlank() }?.action ?: ""
            val mergedEmoji = deliveryItems.joinToString("") { it.emoji }
            val mergedDirectorPrompt = deliveryItems.firstOrNull { it.directorPrompt.isNotBlank() }?.directorPrompt ?: ""

            if (mergedReplyText.isBlank() && mergedEmoji.isBlank()) {
                Log.w(TAG, "LLM 重试后仍未返回有效回复")
                // 首次问候无有效内容：回退状态机（Task 15）
                if (effectiveScene == ConversationScene.FIRST_MEETING_GREETING) {
                    try { firstMeetingCoordinator.onGreetingLlmFailure(agentId) } catch (e: Exception) {
                        Log.w(TAG, "onGreetingLlmFailure 失败（空合并回复）", e)
                    }
                }
                return false
            }
            if (splitItems.size > 1) {
                Log.d(TAG, "防御合并：${splitItems.size} 条 → 1 条，replyText=${mergedReplyText.replace("\n", " / ")}, action=$mergedAction, emoji=$mergedEmoji")
            }

            // 纯 emoji（无文字）：不合成语音，直接入库
            if (mergedEmoji.isNotBlank() && mergedReplyText.isBlank()) {
                // 校验 cancel 信号（虽无 TTS，但 LLM 调用阶段也可能被取消）
                coroutineContext.ensureActive()
                // 去重检查（防竞态导致重复入库）
                if (isDuplicateReply(mergedEmoji)) {
                    Log.w(TAG, "回复 emoji 与最近 10 秒内已入库的重复，跳过（防竞态去重）")
                    return false
                }
                val emojiMsg = ChatMessageEntity(
                    agentId = agentId,
                    direction = "outbound",
                    text = null,
                    audioPath = null,
                    directorPrompt = null,
                    state = com.agent.ta.service.AgentEngine.currentState.value.id,
                    status = "sent",
                    createdAt = System.currentTimeMillis(),
                    emoji = mergedEmoji
                )
                chatDao.insert(emojiMsg)
                notificationHelper.notifyAgentMessage(mergedEmoji, null, resolveAgentName(agentId))
            } else {
                // 文字消息：合成语音只朗读 replyText
                var audioPath: String? = null
                var audioDurationSec: Int? = null
                if (prefs.voiceEnabled && mergedReplyText.isNotBlank()) {
                    // 合并兜底路径：取第一条 reply 的 emotion（空则 fallback neutral）
                    val mergedEmotion = splitItems.firstOrNull { it.emotion.isNotBlank() }?.emotion ?: ""
                    val result = synthesizeVoice(mergedReplyText, mergedDirectorPrompt, mergedEmotion)
                    audioPath = result?.first
                    audioDurationSec = result?.second
                }

                // TTS 期间用户可能发了新消息触发 cancel，此处检查避免被取消的旧回复仍入库
                // （synthesizeVoice 是远程阻塞调用，cancel 信号要等其返回才能生效）
                coroutineContext.ensureActive()

                // 去重检查（防竞态导致重复入库）
                if (isDuplicateReply(mergedReplyText)) {
                    Log.w(TAG, "回复内容与最近 10 秒内已入库的重复，跳过（防竞态去重）")
                    return false
                }

                // 同时有文字和 emoji 时忽略 emoji（语音消息只保留文字，避免 emoji 挤在语音气泡里）
                val agentMsg = ChatMessageEntity(
                    agentId = agentId,
                    direction = "outbound",
                    text = mergedReplyText,
                    audioPath = audioPath,
                    directorPrompt = mergedDirectorPrompt,
                    state = com.agent.ta.service.AgentEngine.currentState.value.id,
                    status = "sent",
                    createdAt = System.currentTimeMillis(),
                    action = mergedAction?.takeIf { it.isNotBlank() },
                    audioDurationSec = audioDurationSec,
                    emoji = null  // 忽略 emoji
                )
                ensureOperationCurrent(operationContext)
                chatDao.insert(agentMsg)

                val notifyText = buildString {
                    append(mergedReplyText.replace("\n", " "))
                    if (mergedEmoji.isNotBlank()) {
                        if (isNotEmpty()) append(" ")
                        append(mergedEmoji)
                    }
                }
                notificationHelper.notifyAgentMessage(notifyText, audioPath, resolveAgentName(agentId))
            }

            // 首次见面状态推进（Task 15）
            advanceFirstMeetingState(agentId, effectiveScene)

            // 记录回复完成时间，用于连续对话检测
            lastReplyTime = System.currentTimeMillis()

            // Phase 2 关系系统：推进数值 + 处理 LLM 声明的里程碑
            try {
                val replyTextForRelationship = items.joinToString(" ") { it.replyText }.take(500)
                val emotionForRelationship = items.firstOrNull()?.emotion?.ifBlank { "neutral" } ?: "neutral"
                val totalLength = items.sumOf { it.replyText.length }
                    relationshipService.onTurnCompleted(
                    agentId = agentId,
                    emotion = emotionForRelationship,
                    isUserInitiated = true,
                    messageLength = totalLength
                )
                // 处理 LLM 主动声明的里程碑
                val declaredMilestone = reply.milestoneDeclared
                if (!declaredMilestone.isNullOrBlank() && declaredMilestone != "null") {
                    val title = RelationshipService.MILESTONE_TITLE_MAP[declaredMilestone] ?: declaredMilestone
                    relationshipService.recordMilestone(
                        agentId = agentId,
                        type = declaredMilestone,
                        title = title,
                        source = "llm_declared",
                        context = mapOf("replyText" to replyTextForRelationship)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "关系状态推进失败（不影响主流程）", e)
            }

            // Phase 3 情感势能：根据 LLM 自报情绪强度推进 valence/arousal/势能
            try {
                val emotionForEmotional = items.firstOrNull()?.emotion?.ifBlank { "neutral" } ?: "neutral"
                emotionalService.onTurnCompleted(
                    agentId = agentId,
                    emotionIntensity = reply.emotionIntensity,
                    emotion = emotionForEmotional
                )
            } catch (e: Exception) {
                Log.e(TAG, "情绪状态推进失败（不影响主流程）", e)
            }

            // Agent 自主切换头像：LLM 输出 wantAvatarId 时，更新 currentAvatarId
            // 指向的头像 id 不存在时静默忽略（保持原头像），避免 LLM 误输出导致头像消失
            val wantAvatarId = reply.wantAvatarId
            if (!wantAvatarId.isNullOrBlank() && wantAvatarId != "null") {
                try {
                    ensureOperationCurrent(operationContext)
                    val currentConfig = configProvider.get()
                    val matched = currentConfig.agent.avatars.firstOrNull { it.id == wantAvatarId }
                    if (matched != null) {
                        if (matched.id != currentConfig.agent.currentAvatarId) {
                            agentConfigEditor.update { cfg ->
                                cfg.copy(
                                    agent = cfg.agent.copy(currentAvatarId = matched.id)
                                )
                            }
                            Log.d(TAG, "Agent 自主切换头像：${matched.id}（${matched.description.ifBlank { "无描述" }}）")
                        }
                    } else {
                        Log.w(TAG, "LLM 输出的 wantAvatarId 不存在：$wantAvatarId，忽略切换")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "应用头像切换失败（不影响主流程）", e)
                }
            }

            return true

        } catch (e: CancellationException) {
            // 用户发新消息，剩余条目被取消，属正常流程
            Log.d(TAG, "回复任务被取消（用户发了新消息或状态切换）")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "生成回复失败", e)
            // 首次问候 LLM 异常：回退状态机（Task 15）
            if (scene == ConversationScene.FIRST_MEETING_GREETING) {
                try { firstMeetingCoordinator.onGreetingLlmFailure(agentId) } catch (ex: Exception) {
                    Log.w(TAG, "onGreetingLlmFailure 失败（异常回退）", ex)
                }
            }
            return false
        }
    }

    /**
     * 处理称呼解析结果（Task 15）
     *
     * 解析 LLM 输出的 nicknameResolution，根据当前是否处于首次见面等待称呼阶段，
     * 分别走不同处理路径：
     *
     * - awaitingNickname=true（首次见面 WAITING_NICKNAME / FOLLOW_UP_ASKED）：
     *   EXPLICIT_NICKNAME/CORRECTION → 校验通过则保存 + onNicknameCaptured
     *   DECLINED → onUserDeclined
     *   其他 → onNicknameUnrecognized
     *
     * - awaitingNickname=false（首次见面已完成或普通对话）：
     *   CORRECTION/EXPLICIT_NICKNAME → 校验通过则更新称呼
     *   CLEAR → 清空称呼
     *   其他 → 忽略
     *
     * 称呼只写入请求所属 agentId 的 AgentConfig，不污染其他 Agent。
     * 不显示「配置保存成功」系统提示，保持人格化自然表达。
     */
    private suspend fun processNicknameResolution(
        agentId: Long,
        reply: com.agent.ta.data.remote.dto.AgentReply,
        awaitingNickname: Boolean
    ) {
        val rawResolution = reply.nicknameResolution ?: return
        val resolution = nicknameResolver.parse(rawResolution)

        if (resolution.intent == "NONE") return

        if (awaitingNickname) {
            // 首次见面进行中：WAITING_NICKNAME / FOLLOW_UP_ASKED
            when (resolution.intent) {
                "EXPLICIT_NICKNAME", "CORRECTION" -> {
                    val decision = nicknameResolver.decideSave(resolution)
                    if (decision.shouldSave && decision.normalizedNickname != null) {
                        saveNicknameToAgent(agentId, decision.normalizedNickname)
                        try {
                            firstMeetingCoordinator.onNicknameCaptured(agentId, decision.normalizedNickname)
                            Log.d(TAG, "首次见面：称呼已捕获 '${decision.normalizedNickname}'")
                        } catch (e: Exception) {
                            Log.w(TAG, "onNicknameCaptured 失败", e)
                        }
                    } else {
                        // 校验未通过，视为未识别
                        try {
                            firstMeetingCoordinator.onNicknameUnrecognized(agentId)
                            Log.d(TAG, "首次见面：称呼校验未通过（${decision.reason}），视为未识别")
                        } catch (e: Exception) {
                            Log.w(TAG, "onNicknameUnrecognized 失败", e)
                        }
                    }
                }
                "CLEAR" -> {
                    // 首次见面中要求清空：尚无称呼可清，视为未识别
                    try {
                        firstMeetingCoordinator.onNicknameUnrecognized(agentId)
                        Log.d(TAG, "首次见面：用户要求清空但尚无称呼，视为未识别")
                    } catch (e: Exception) {
                        Log.w(TAG, "onNicknameUnrecognized 失败(CLEAR)", e)
                    }
                }
                "DECLINED" -> {
                    try {
                        firstMeetingCoordinator.onUserDeclined(agentId)
                        Log.d(TAG, "首次见面：用户明确拒绝提供称呼")
                    } catch (e: Exception) {
                        Log.w(TAG, "onUserDeclined 失败", e)
                    }
                }
                "SELF_INTRODUCTION", "AMBIGUOUS" -> {
                    try {
                        firstMeetingCoordinator.onNicknameUnrecognized(agentId)
                        Log.d(TAG, "首次见面：未识别到明确称呼（intent=${resolution.intent}）")
                    } catch (e: Exception) {
                        Log.w(TAG, "onNicknameUnrecognized 失败(${resolution.intent})", e)
                    }
                }
            }
        } else {
            // 首次见面已完成或普通对话：处理 CORRECTION / CLEAR / EXPLICIT_NICKNAME
            when (resolution.intent) {
                "EXPLICIT_NICKNAME", "CORRECTION" -> {
                    val decision = nicknameResolver.decideSave(resolution)
                    if (decision.shouldSave && decision.normalizedNickname != null) {
                        saveNicknameToAgent(agentId, decision.normalizedNickname)
                        Log.d(TAG, "称呼已更新：agentId=$agentId nickname='${decision.normalizedNickname}'")
                    } else {
                        Log.d(TAG, "称呼更新校验未通过：${decision.reason}")
                    }
                }
                "CLEAR" -> {
                    if (nicknameResolver.shouldClear(resolution)) {
                        clearNicknameFromAgent(agentId)
                        Log.d(TAG, "称呼已清空：agentId=$agentId")
                    }
                }
                // SELF_INTRODUCTION / DECLINED / AMBIGUOUS / NONE：不处理
            }
        }
    }

    /**
     * 保存称呼到指定 Agent 的配置（Task 15）
     *
     * 通过 AgentConfigEditor.updateAgent 按 agentId 写入，确保只更新目标 Agent。
     * 同时同步全局 prefs.userNickname（向后兼容，PromptBuilder 兜底读取）。
     */
    private suspend fun saveNicknameToAgent(agentId: Long, nickname: String) {
        try {
            agentConfigEditor.updateAgent(agentId) { config ->
                val persona = config.agent.persona
                config.copy(
                    agent = config.agent.copy(
                        persona = persona.copy(nicknameForUser = nickname)
                    )
                )
            }
            // 同步全局 prefs（向后兼容）
            prefs.userNickname = nickname
        } catch (e: Exception) {
            Log.e(TAG, "保存称呼失败：agentId=$agentId", e)
        }
    }

    /**
     * 清空指定 Agent 的称呼（Task 15）
     */
    private suspend fun clearNicknameFromAgent(agentId: Long) {
        try {
            agentConfigEditor.updateAgent(agentId) { config ->
                val persona = config.agent.persona
                config.copy(
                    agent = config.agent.copy(
                        persona = persona.copy(nicknameForUser = "")
                    )
                )
            }
            // 同步全局 prefs
            prefs.userNickname = "你"
        } catch (e: Exception) {
            Log.e(TAG, "清空称呼失败：agentId=$agentId", e)
        }
    }

    /**
     * 推进首次见面状态机（Task 15）
     *
     * 在回复消息持久化后调用：
     * - FIRST_MEETING_GREETING：问候消息已入库 → onGreetingSuccess → WAITING_NICKNAME
     * - FIRST_MEETING_REPLY：用户先发消息的首次回复已入库 → markGreetingCompletedIfInProgress → WAITING_NICKNAME
     *
     * 查询最新的 outbound 消息 ID 作为 greetingMessageId（用于幂等防重复）。
     * 失败不阻塞主流程。
     */
    private suspend fun advanceFirstMeetingState(agentId: Long, scene: ConversationScene) {
        try {
            when (scene) {
                ConversationScene.FIRST_MEETING_GREETING -> {
                    val now = System.currentTimeMillis()
                    val recentMessages = chatDao.getAll(agentId)
                    val greetingMsg = recentMessages.lastOrNull { it.direction == "outbound" }
                    val msgId = greetingMsg?.id ?: 0L
                    firstMeetingCoordinator.onGreetingSuccess(agentId, msgId, now)
                }
                ConversationScene.FIRST_MEETING_REPLY -> {
                    val now = System.currentTimeMillis()
                    val recentMessages = chatDao.getAll(agentId)
                    val greetingMsg = recentMessages.lastOrNull { it.direction == "outbound" }
                    val msgId = greetingMsg?.id ?: 0L
                    firstMeetingCoordinator.markGreetingCompletedIfInProgress(agentId, msgId, now)
                }
                else -> {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "推进首次见面状态失败（不影响主流程）", e)
        }
    }

    /**
     * 调用 LLM，支持多轮工具调用循环（v3 function calling）
     *
     * 工具调用启用场景：
     * - 正常用户消息回复
     * - Agent 主动发起 / 首次见面问候
     *
     * 跳过工具调用的场景：
     * - 配置模式（专注配置变更，不需要外部工具）
     * - 补回复场景（之前发的消息，简单回应即可，避免延迟过久）
     * - 未注册任何工具时
     *
     * 多轮工具调用：
     * - 最多 MAX_TOOL_ROUNDS 轮，防死循环
     * - 每轮：LLM 决定调用工具 → App 执行 → 结果回传 → LLM 继续推理
     * - LLM 可在任意轮次选择直接回复（不调用工具），结束循环
     * - 工具调用整体失败时回退到无工具 chat（避免工具系统故障阻塞对话）
     */
    private suspend fun callLlmWithToolSupport(
        messages: List<ChatMessage>,
        isConfigMode: Boolean,
        isPendingCatchup: Boolean
    ): com.agent.ta.data.remote.dto.AgentReply {
        // 特殊场景跳过工具调用
        if (isConfigMode || isPendingCatchup || !toolRegistry.hasTools()) {
            return llmClient.chat(messages)
        }

        val tools = toolRegistry.getAllDefinitions()
        var currentMessages = messages

        repeat(MAX_TOOL_ROUNDS) { round ->
            coroutineContext.ensureActive()
            when (val resp = llmClient.chatWithTools(currentMessages, tools)) {
                is ToolCallResponse.Reply -> {
                    // LLM 直接回复（未调用工具，或工具调用后已生成最终回复）
                    if (round > 0) {
                        Log.d(TAG, "工具调用第 ${round + 1} 轮后 LLM 给出最终回复")
                    }
                    return resp.reply
                }
                is ToolCallResponse.ToolCalls -> {
                    Log.d(TAG, "工具调用第 ${round + 1} 轮：${resp.toolCalls.size} 个工具 - ${resp.toolCalls.joinToString { it.function.name }}")

                    // 加入 assistant 消息（含 tool_calls，OpenAI 协议要求）
                    currentMessages = currentMessages + resp.assistantMessage

                    // 构造工具执行上下文
                    val toolContext = ToolContext(
                        agentConfig = configProvider.get(),
                        userMessage = extractLastUserMessage(messages),
                        conversationHistory = messages,
                        appContext = context,
                        scope = scope
                    )

                    // 并行执行所有工具调用
                    val results = toolRegistry.executeToolCalls(resp.toolCalls, toolContext)

                    // 加入每条工具结果消息（tool 角色）
                    results.forEach { result ->
                        Log.d(TAG, "工具 ${result.toolName} 执行完成：${if (result.result is com.agent.ta.domain.tool.ToolResult.Success) "成功" else "失败"}")
                        currentMessages = currentMessages + ChatMessage(
                            role = "tool",
                            content = result.toMessageContent(),
                            toolCallId = result.toolCallId,
                            name = result.toolName
                        )
                    }
                }
            }
        }

        // 达到最大轮次仍有工具调用，强制要求 LLM 给出最终回复
        Log.w(TAG, "工具调用达到最大轮次 $MAX_TOOL_ROUNDS，强制要求 LLM 回复")
        currentMessages = currentMessages + ChatMessage(
            role = "system",
            content = "已经进行了 $MAX_TOOL_ROUNDS 轮工具调用，请基于已有信息直接给出最终回复，不要再调用工具。"
        )
        return llmClient.chat(currentMessages)
    }

    /**
     * 从消息列表中提取最后一条 user 消息内容（供工具上下文使用）
     */
    private fun extractLastUserMessage(messages: List<ChatMessage>): String {
        return messages.lastOrNull { it.role == "user" }?.content ?: ""
    }

    /**
     * 应用配置变更（配置模式下 LLM 输出的 configUpdate）
     *
     * 只更新非 null 字段，其他字段保持原值。
     * 通过 AgentConfigEditor.update 原子性地写入数据库。
     */
    private suspend fun applyConfigUpdate(agentId: Long, update: com.agent.ta.data.remote.dto.ConfigUpdate) {
        val hasChange = update.name != null ||
            update.gender != null ||
            update.age != null ||
            update.background != null ||
            update.personality != null ||
            update.speakingStyle != null ||
            update.selfNickname != null ||
            update.nicknameForUser != null ||
            update.relationshipToUser != null ||
            update.catchphrases != null ||
            update.interests != null ||
            update.taboos != null

        if (!hasChange) {
            Log.d(TAG, "configUpdate 无变更字段，跳过")
            return
        }

        try {
            ServiceLocator.configSessionManager.applyUpdate(agentId, update)
            Log.d(TAG, "配置草稿已更新：${update.summary}")
        } catch (e: Exception) {
            Log.e(TAG, "应用配置变更失败", e)
        }
    }

    /**
     * 统计对话历史末尾连续的 inbound（用户）消息条数
     * 用于 PromptBuilder 判断是否走"多消息对应回复"路径
     */
    private fun countTrailingInbound(messages: List<ChatMessageEntity>): Int {
        if (messages.isEmpty()) return 0
        var count = 0
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            if (msg.direction == "inbound" && !msg.text.isNullOrBlank()) {
                count++
            } else if (msg.direction == "outbound") {
                break
            }
        }
        return count
    }

    /**
     * 多消息独立入库：每条 reply 作为独立消息显示（像真人微信连发）
     *
     * TTS 策略：
     * - 每条独立 TTS 合成，避免合并成一条长语音
     * - 短句独立语音更自然，且每条都能单独播放
     */
    private suspend fun persistMultipleReplies(
        agentId: Long,
        operationContext: AgentOperationContext,
        replies: List<ReplyItem>
    ): Boolean {
        if (replies.isEmpty()) return false

        // 去重检查：拼接所有 replyText 作为整体指纹
        // 防止竞态导致相同内容被两次 generateAgentReply 重复入库
        val fingerprint = replies.joinToString(" | ") { it.replyText.ifBlank { it.emoji } }
        if (isDuplicateReply(fingerprint)) {
            Log.w(TAG, "回复内容与最近 10 秒内已入库的重复，跳过（防竞态去重）")
            return false
        }

        val state = com.agent.ta.service.AgentEngine.currentState.value
        var lastSent: ChatMessageEntity? = null

        replies.forEachIndexed { index, reply ->
            coroutineContext.ensureActive()
            ensureOperationCurrent(operationContext)
            val audioResult = if (prefs.voiceEnabled) {
                synthesizeVoice(reply.replyText, reply.directorPrompt, reply.emotion)
            } else {
                null
            }
            coroutineContext.ensureActive()
            ensureOperationCurrent(operationContext)
            val audioPath = audioResult?.first
            val audioDuration = audioResult?.second

            val msg = ChatMessageEntity(
                agentId = agentId,
                direction = "outbound",
                text = reply.replyText.takeIf { it.isNotBlank() },
                audioPath = audioPath,
                directorPrompt = reply.directorPrompt.takeIf { it.isNotBlank() },
                state = state.id,
                status = "sent",
                createdAt = System.currentTimeMillis() + index,
                action = reply.action.takeIf { it.isNotBlank() },
                audioDurationSec = audioDuration,
                emoji = reply.emoji.takeIf { it.isNotBlank() }
            )
            chatDao.insert(msg)
            lastSent = msg
        }

        val sent = lastSent ?: return false
        val notifyText = buildString {
            append(sent.text.orEmpty())
            if (!sent.emoji.isNullOrBlank()) {
                if (isNotEmpty()) append(" ")
                append(sent.emoji)
            }
        }
        notificationHelper.notifyAgentMessage(notifyText, sent.audioPath, resolveAgentName(agentId))
        return true
    }

    /**
     * 合成语音
     *
     * @param emotion 该条回复的情绪标签（neutral/happy/calm），用于选择对应情绪的样本和参数。
     *                空字符串或未知值时由 VoiceConfig 内部 fallback 到 neutral。
     *
     * @return (音频文件路径, 时长秒)，失败返回 null
     *
     * 降级策略：
     * - 配置了克隆样本时：远程 TTS 失败不降级系统 TTS（音色差异太大，宁可不发语音）
     * - 未配置样本（用预置/设计音色）时：远程失败可降级系统 TTS
     * - 重试间隔 1500ms，给限流更多恢复时间
     */
    private suspend fun synthesizeVoice(text: String, directorPrompt: String, emotion: String): Pair<String, Int>? {
        val speechText = TtsTextPolicy.sanitizeForSpeech(text)
        if (speechText.isBlank()) return null
        val config = configProvider.get()
        val voiceConfig = config.voice
        val samplePath = voiceConfig.sampleFile.takeIf { it.isNotBlank() }
        // 是否配置了克隆样本（任何情绪有样本都算）
        val hasCloneSample = voiceConfig.sampleFileFor(emotion)?.isNotBlank() == true

        Log.d(TAG, "合成语音：emotion=$emotion, samplePath=$samplePath, hasCloneSample=$hasCloneSample, directorMode=${voiceConfig.directorMode}, ttsBaseUrl=${prefs.ttsBaseUrl}, ttsApiKey配置=${prefs.ttsApiKey.isNotBlank()}, textLen=${speechText.length}")

        // 每条 reply 整体一次 TTS 请求，生成一条完整连贯的语音
        // 消息拆分已由 splitLongReply 在更上层完成（按句末标点拆成多条独立 reply）
        var audioResult = tryRemoteTts(speechText, directorPrompt, samplePath, voiceConfig, emotion)
        if (audioResult == null) {
            Log.w(TAG, "远程 TTS 第一次失败，1500ms 后重试")
            delay(1500)
            audioResult = tryRemoteTts(speechText, directorPrompt, samplePath, voiceConfig, emotion)
        }

        if (audioResult != null) {
            val audioFile = File(context.cacheDir, "voice_${UUID.randomUUID()}.${audioResult.format}")
            audioFile.writeBytes(audioResult.bytes)
            val durationSec = getAudioDurationSec(audioFile.absolutePath)
            Log.d(TAG, "远程 TTS 合成成功：${audioFile.absolutePath}，${audioResult.bytes.size} bytes，${durationSec}s")
            return Pair(audioFile.absolutePath, durationSec)
        }

        // 降级策略：配置了克隆样本时不降级系统 TTS（音色差异太大）
        if (hasCloneSample) {
            Log.w(TAG, "远程 TTS 失败，但已配置克隆样本，不降级系统 TTS（避免音色不一致）")
            return null
        }

        Log.w(TAG, "远程 TTS 两次均失败，降级系统 TTS")
        return try {
            val file = systemTtsSynthesizer.synthesize(speechText)
            if (file != null) {
                val durationSec = getAudioDurationSec(file.absolutePath)
                Pair(file.absolutePath, durationSec)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "系统 TTS 合成失败", e)
            null
        }
    }

    /**
     * 封装远程 TTS 调用（synthesize 已在 TtsClient 内部切到 IO 线程）
     */
    private suspend fun tryRemoteTts(
        text: String,
        directorPrompt: String,
        samplePath: String?,
        voiceConfig: com.agent.ta.data.model.VoiceConfig? = null,
        emotionHint: String? = null
    ): com.agent.ta.data.remote.TtsAudioResult? {
        return try {
            ttsClient.synthesize(text, directorPrompt, samplePath, voiceConfig, emotionHint)
        } catch (e: CancellationException) {
            throw e  // 取消信号必须向上传播
        } catch (e: Exception) {
            Log.e(TAG, "远程 TTS 异常", e)
            null
        }
    }

    /**
     * 用 MediaMetadataRetriever 读取音频文件时长（秒）
     * 用于 VoiceBubble 显示真实时长而非写死 5 秒
     */
    private fun getAudioDurationSec(path: String): Int {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val ms = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            ((ms + 500) / 1000).toInt().coerceAtLeast(1)
        } catch (e: Exception) {
            Log.w(TAG, "读取音频时长失败：$path", e)
            1
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // 忽略 release 失败
            }
        }
    }

    /**
     * 动态调整记忆重要性
     * 
     * 算法：
     * 1. 每次被引用时增加 accessCount
     * 2. 根据 accessCount 和时间衰减重新计算 importance
     * 3. importance = min(5, baseImportance + accessCount/5 - daysSinceUpdate/30)
     */
    private suspend fun adjustMemoryImportance(agentId: Long, memories: List<MemoryEntity>) {
        val now = System.currentTimeMillis()
        memories.forEach { memory ->
            // 增加访问计数
            memoryDao.incrementAccessCount(agentId, memory.id, now)
            
            // 计算新的 importance
            val daysSinceUpdate = (now - memory.updatedAt) / (1000 * 60 * 60 * 24)
            val accessBonus = memory.accessCount / 5
            val timeDecay = daysSinceUpdate / 30
            val newImportance = (memory.importance + accessBonus - timeDecay.toInt()).coerceIn(1, 5)
            
            // 如果 importance 变化超过 1，更新数据库
            if (kotlin.math.abs(newImportance - memory.importance) > 1) {
                memoryDao.updateImportance(agentId, memory.id, newImportance, now)
            }
        }
    }

    /**
     * 将 ISO 8601 字符串转换为时间戳（毫秒）
     * 输入格式如 "2026-07-30T15:00:00"
     */
    private fun parseIso8601ToTimestamp(iso8601: String): Long? {
        return try {
            val ldt = java.time.LocalDateTime.parse(iso8601)
            ldt.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        } catch (e: Exception) {
            Log.w(TAG, "解析 ISO 8601 时间失败：$iso8601", e)
            null
        }
    }

    /**
     * 构建昨日状态延续文本（Step 24）
     * 读取昨日 DailyStateEntity，构造睡眠/活动/情绪/调整建议的语义化文本
     *
     * 输出示例：
     * 昨晚你 01:15 才睡，今早 07:30 起（睡眠 6 小时 15 分钟，略不足）
     * 昨天活动：赶设计稿、加班到深夜
     * 昨天状态：压力大（stress=0.7）、疲劳（fatigue=0.8）、心情一般（mood=-0.2）
     * 今天调整建议：
     * - 起床时间可以晚 30 分钟
     * - 多安排休息时段（疲劳未恢复）
     */
    private suspend fun buildYesterdayCarryOver(agentId: Long): String? {
        return try {
            val zone = java.time.ZoneId.of("Asia/Shanghai")
            val yesterday = java.time.LocalDate.now(zone).minusDays(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val state = dailyStateDao.getByDate(agentId, yesterday) ?: return null

            val sb = StringBuilder()

            // 睡眠信息
            if (!state.sleepTime.isNullOrBlank() && !state.wakeTime.isNullOrBlank()) {
                val durationStr = state.sleepDurationMin?.let { min ->
                    val hours = min / 60
                    val mins = min % 60
                    val adequacy = when {
                        min < 360 -> "严重不足"
                        min < 420 -> "略不足"
                        min < 540 -> "适中"
                        else -> "充足"
                    }
                    "$hours 小时 $mins 分钟（$adequacy）"
                } ?: ""
                sb.appendLine("昨晚你 ${state.sleepTime} 才睡，今早 ${state.wakeTime} 起（睡眠 $durationStr）")
            }

            // 昨天活动
            val activities = parseActivitiesJson(state.mainActivities)
            if (activities.isNotEmpty()) {
                sb.appendLine("昨天活动：${activities.joinToString("、")}")
            }

            // 昨天状态（情绪/压力/疲劳）
            val stateParts = mutableListOf<String>()
            state.stress?.let {
                val level = if (it > 0.6) "压力大" else if (it > 0.3) "有些压力" else "轻松"
                stateParts.add("$level（stress=$it）")
            }
            state.fatigue?.let {
                val level = if (it > 0.6) "疲劳" else if (it > 0.3) "有些累" else "精神"
                stateParts.add("$level（fatigue=$it）")
            }
            state.mood?.let {
                val level = if (it > 0.3) "心情好" else if (it > -0.3) "心情一般" else "心情低落"
                stateParts.add("$level（mood=$it）")
            }
            if (stateParts.isNotEmpty()) {
                sb.appendLine("昨天状态：${stateParts.joinToString("、")}")
            }

            // 互动信息
            if (state.hadInteractionWithUser) {
                sb.appendLine("昨天和用户聊了 ${state.interactionCount} 条消息")
            } else {
                sb.appendLine("昨天没有和用户互动")
            }

            // 今日调整建议（基于睡眠/疲劳/压力生成）
            val suggestions = mutableListOf<String>()
            state.sleepDurationMin?.let { min ->
                if (min < 420) {
                    suggestions.add("起床时间可以晚 30 分钟")
                    suggestions.add("多安排休息时段（疲劳未恢复）")
                }
            }
            state.fatigue?.let {
                if (it > 0.6) suggestions.add("多安排休息时段（疲劳未恢复）")
            }
            state.stress?.let {
                if (it > 0.6) suggestions.add("安排些放松活动缓解压力")
            }
            if (suggestions.isNotEmpty()) {
                sb.appendLine("今天调整建议：")
                suggestions.distinct().forEach { sb.appendLine("- $it") }
            }

            val result = sb.toString().trim()
            if (result.isBlank()) null else result
        } catch (e: Exception) {
            Log.w(TAG, "构建昨日状态延续失败", e)
            null
        }
    }

    /**
     * 解析 mainActivities JSON 数组字符串为列表
     * 简单解析 ["a","b","c"] 格式，不依赖序列化库
     */
    private fun parseActivitiesJson(jsonStr: String): List<String> {
        if (jsonStr.isBlank() || jsonStr == "[]") return emptyList()
        return try {
            jsonStr.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val TAG = "ChatInteractor"

        // 工具调用最大轮次（防死循环）
        private const val MAX_TOOL_ROUNDS = 3

        // 一致性校验最大重试次数（校验失败时追加修正指令重试）
        private const val MAX_CONSISTENCY_RETRIES = 2

        // 当前进行中的回复任务（跨实例共享，用户发新消息时取消）
        // 使用 AtomicReference 避免竞态条件
        private val currentReplyJobRef = AtomicReference<Job?>(null)

        private val pendingReplyJobRef = AtomicReference<Job?>(null)

        private val commitmentReplyMutex = Mutex()

        suspend fun cancelAndJoinForAgentSwitch() {
            currentReplyJobRef.getAndSet(null)?.cancelAndJoin()
            pendingReplyJobRef.getAndSet(null)?.cancelAndJoin()
            _isReplying.value = false
        }

        // Agent 是否正在输入/生成回复（供 UI 显示"正在输入中"指示器）
        // 跨实例共享：ChatViewModel 的 ChatInteractor 和 AgentEngine 创建的实例共享同一状态
        private val _isReplying = MutableStateFlow(false)
        val isReplying: StateFlow<Boolean> = _isReplying.asStateFlow()

        // 配置模式状态（用户输入 /config 进入，/done 退出）
        // 跨实例共享：确保 ChatViewModel 和 AgentEngine 创建的实例共享同一配置模式状态
        private val _configMode = MutableStateFlow(false)
        val configMode: StateFlow<Boolean> = _configMode.asStateFlow()

        // 括号动作提取正则（编译一次复用）
        private val BRACKET_REGEX = Regex("[（(]([^）)]*)[）)]")

        // === 连续对话节奏优化 ===
        // 真人场景：忙碌时第一次回复慢，之后用户继续聊会快速来回几条，最后说"先去忙了"
        // 避免：用户发 → 等延迟 → 回复 → 用户发 → 又等延迟 → 回复（机械节奏）

        /** 连续对话判定阈值：Agent 回复后 90 秒内用户继续发消息，算连续对话 */
        private const val CONTINUOUS_CHAT_THRESHOLD_MS = 90 * 1000L

        /** 连续对话时的短延迟范围（秒）：3-8 秒，让对话快速来回 */
        private val CONTINUOUS_DELAY_RANGE = 3..8

        private val LIGHT_SLEEP_DELAY_RANGE = 10..30

        /** 连续对话超过此次数后，prompt 提示 Agent 可以主动结束对话 */
        private const val CONTINUOUS_CHAT_END_HINT = 3

        /** 上次 Agent 回复完成的时间戳（跨实例共享） */
        @Volatile
        private var lastReplyTime: Long = 0L

        /** 当前连续对话轮次（每轮 = 用户发 + Agent 回），跨实例共享 */
        @Volatile
        private var continuousRound: Int = 0

        // === 回复去重缓存（防竞态导致重复入库）===
        // 场景：sendUserMessage 的回复任务和 Heartbeat/Initiator 触发的 agentInitiate 可能竞态，
        // 两次 generateAgentReply 几乎同时执行，LLM 上下文相似导致返回相同内容。
        // 这里记录最近入库的 replyText，短时间内重复则跳过。
        private val recentReplyTexts = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private const val DEDUP_WINDOW_MS = 10_000L  // 10 秒去重窗口

        /**
         * 检查并标记回复内容是否为重复
         * - 如果在 [DEDUP_WINDOW_MS] 窗口内已有相同内容入库，返回 true（重复，跳过）
         * - 否则记录当前时间戳，返回 false（非重复，继续入库）
         */
        private fun isDuplicateReply(text: String): Boolean {
            val now = System.currentTimeMillis()
            // 清理过期记录
            recentReplyTexts.entries.removeAll { (_, ts) -> now - ts > DEDUP_WINDOW_MS }
            val key = text.trim()
            if (key.isBlank()) return false
            val existing = recentReplyTexts[key]
            if (existing != null && now - existing < DEDUP_WINDOW_MS) {
                return true
            }
            recentReplyTexts[key] = now
            return false
        }
    }
}

/**
 * 计算消息相对当前时间的自然语言描述
 *
 * 用于在喂给 LLM 的对话历史/pending 合并文本/日报摘要中标注消息发送时间，
 * 替代原有的 [MM-dd HH:mm] 方括号时间戳格式。
 *
 * 原因：方括号时间戳格式（如 [08-03 14:30]）会被 LLM 模仿，
 * 导致回复中把多条消息塞进一个 replyText 并用时间戳分隔。
 * 改用「刚刚」「3分钟前」「昨天」等自然语言，LLM 不会在回复中模仿这种格式。
 */
internal fun relativeTimeGap(millis: Long): String {
    val safeMillis = millis.coerceAtLeast(0L)
    return when {
        safeMillis < 60_000L -> "刚刚"
        safeMillis < 3_600_000L -> "${safeMillis / 60_000L}分钟前"
        safeMillis < 86_400_000L -> "${safeMillis / 3_600_000L}小时前"
        safeMillis < 2 * 86_400_000L -> "昨天"
        safeMillis < 7 * 86_400_000L -> "${safeMillis / 86_400_000L}天前"
        else -> "很久以前"
    }
}
