package com.agent.ta.domain

import android.content.Context
import android.util.Log
import com.agent.ta.data.local.entity.ChatMessageEntity
import com.agent.ta.data.local.entity.FutureEventEntity
import com.agent.ta.data.local.entity.MemoryEntity
import com.agent.ta.data.model.AgentState
import com.agent.ta.data.remote.LlmClient.ToolCallResponse
import com.agent.ta.data.remote.dto.ChatMessage
import com.agent.ta.data.remote.dto.ReplyItem
import com.agent.ta.di.ServiceLocator
import com.agent.ta.domain.consistency.ReplyConsistencyValidator
import com.agent.ta.domain.tool.ToolContext
import com.agent.ta.service.NotificationHelper
import com.agent.ta.util.SystemTtsSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val prefs = ServiceLocator.userPreferences
    private val configProvider = ServiceLocator.agentConfigProvider
    private val notificationHelper = NotificationHelper(context)
    private val scheduleAdjuster = ScheduleAdjuster()
    private val systemTtsSynthesizer = SystemTtsSynthesizer(context)
    private val consistencyValidator = ReplyConsistencyValidator()

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
                exitConfigMode()
                return
            }
            trimmed == "/help" || trimmed == "/帮助" -> {
                showCommandHelp()
                return
            }
        }

        // 1. 取消上一个进行中的回复任务（包括延迟阶段，防止快速连发产生多个并行任务）
        currentReplyJobRef.get()?.cancel()
        currentReplyJobRef.set(null)

        // 2. 立即启动可取消的回复任务（包括入库、延迟、生成回复，都在同一个 Job 中）
        val job = scope.launch {
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
            val initialStatus = when {
                state == AgentState.UNAVAILABLE -> "pending"
                isBusyLongDelay -> "pending"  // busy 非连续对话：延迟期间未读
                else -> "received"  // 连续对话或 normal/idle：直接已读
            }
            val userMsg = ChatMessageEntity(
                direction = "inbound",
                text = text,
                audioPath = null,
                directorPrompt = null,
                state = state.id,
                status = initialStatus,
                createdAt = System.currentTimeMillis()
            )
            val msgId = chatDao.insert(userMsg)

            // 4. 判定当前状态
            if (state == AgentState.UNAVAILABLE) {
                // 不可回复，保持 pending（由 StateMachine 状态切换后触发 processPendingReplies）
                chatDao.updateStatus(msgId, "pending", null)
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
                val delaySec = if (isContinuousChat) {
                    CONTINUOUS_DELAY_RANGE.random().toLong()
                } else {
                    resolveTypingDelaySec(state)
                }
                delay(delaySec * 1000)

                // busy 非连续对话：延迟结束后才标记已读 + 显示"正在输入中"
                if (isBusyLongDelay) {
                    chatDao.updateStatus(msgId, "received", System.currentTimeMillis())
                    _isReplying.value = true
                }

                // 传入连续对话轮次，用于 prompt 提示
                generateAgentReply(
                    isConfigMode = configMode.value,
                    continuousRound = continuousRound
                )
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
            val state = com.agent.ta.service.AgentEngine.currentState.value
            val msg = ChatMessageEntity(
                direction = "outbound",
                text = "好的，进入配置模式啦 🛠️\n你可以直接用对话告诉我想调整什么（比如：名字、性格、说话风格、语音、头像、行为习惯），我会帮你修改。\n也可以去「设置 → Agent 配置」里可视化编辑。\n完成后输入 /done 退出配置模式。",
                audioPath = null,
                directorPrompt = null,
                state = state.id,
                status = "sent",
                createdAt = System.currentTimeMillis()
            )
            chatDao.insert(msg)
            notificationHelper.notifyAgentMessage(msg.text ?: "", null)
        })
    }

    /**
     * 退出配置模式（用户输入 /done 触发）
     */
    private fun exitConfigMode() {
        if (!_configMode.value) {
            // 不在配置模式，提示
            scope.launch {
                val state = com.agent.ta.service.AgentEngine.currentState.value
                val msg = ChatMessageEntity(
                    direction = "outbound",
                    text = "现在不在配置模式哦～输入 /config 可以进入配置模式",
                    audioPath = null,
                    directorPrompt = null,
                    state = state.id,
                    status = "sent",
                    createdAt = System.currentTimeMillis()
                )
                chatDao.insert(msg)
                notificationHelper.notifyAgentMessage(msg.text ?: "", null)
            }
            return
        }
        _configMode.value = false
        currentReplyJobRef.get()?.cancel()
        currentReplyJobRef.set(scope.launch {
            val state = com.agent.ta.service.AgentEngine.currentState.value
            val msg = ChatMessageEntity(
                direction = "outbound",
                text = "配置已保存 ✅ 继续聊天吧～",
                audioPath = null,
                directorPrompt = null,
                state = state.id,
                status = "sent",
                createdAt = System.currentTimeMillis()
            )
            chatDao.insert(msg)
            notificationHelper.notifyAgentMessage(msg.text ?: "", null)
        })
    }

    /**
     * 显示命令帮助（用户输入 /help 触发）
     */
    private fun showCommandHelp() {
        scope.launch {
            val state = com.agent.ta.service.AgentEngine.currentState.value
            val msg = ChatMessageEntity(
                direction = "outbound",
                text = "可用命令：\n/config - 进入 Agent 配置模式\n/done - 退出配置模式\n/help - 显示命令帮助",
                audioPath = null,
                directorPrompt = null,
                state = state.id,
                status = "sent",
                createdAt = System.currentTimeMillis()
            )
            chatDao.insert(msg)
            notificationHelper.notifyAgentMessage(msg.text ?: "", null)
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
    private fun resolveTypingDelaySec(state: AgentState): Long {
        val typingDuration = configProvider.get().behavior.typingIndicatorDuration[state.id]
        if (typingDuration != null && typingDuration.size >= 2) {
            val min = typingDuration[0].coerceAtLeast(0)
            val max = typingDuration[1].coerceAtLeast(min)
            return (min..max).random().toLong().coerceAtLeast(0L)
        }
        return com.agent.ta.service.AgentEngine.getReplyDelaySec() ?: 1L
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
        scope.launch {
            val pending = chatDao.getPendingMessages()
            if (pending.isEmpty()) return@launch

            val state = com.agent.ta.service.AgentEngine.currentState.value
            if (state == AgentState.UNAVAILABLE) return@launch

            // 把所有 pending 消息合并成一条"补充说明"用户消息入库
            // 让 LLM 知道用户之前说了什么，但不触发逐条对应回复
            val pendingSummary = pending.joinToString("\n") { msg ->
                val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(msg.createdAt))
                val content = buildString {
                    if (!msg.emoji.isNullOrBlank()) append(msg.emoji)
                    if (!msg.text.isNullOrBlank()) {
                        if (isNotEmpty()) append(" ")
                        append(msg.text)
                    }
                }
                "[$timeStr] $content"
            }

            val mergedUserMsg = ChatMessageEntity(
                direction = "inbound",
                text = "（之前发了这些你没来得及回：\n$pendingSummary\n现在简单回应一下就好）",
                audioPath = null,
                directorPrompt = null,
                state = state.id,
                status = "received",
                createdAt = System.currentTimeMillis()
            )
            chatDao.insert(mergedUserMsg)

            // 把原 pending 消息全部标记为 received（已读），避免下次状态切换重复触发
            pending.forEach { msg ->
                chatDao.updateStatus(msg.id, "received", System.currentTimeMillis())
            }

            currentReplyJobRef.get()?.cancel()
            currentReplyJobRef.set(scope.launch {
                try {
                    _isReplying.value = true
                    generateAgentReply(isPendingCatchup = true)
                } finally {
                    _isReplying.value = false
                    currentReplyJobRef.set(null)
                }
            })
        }
    }

    /**
     * Agent 主动发起对话（无聊时 / Onboarding）
     *
     * v2 集成 ThinkActDecider：
     * - topicHint 由 ThinkActDecider.act() 生成，包含话题方向 + persona 引导
     * - topicHint 为空时退化为 v1 行为（无引导，LLM 即兴发挥）
     *
     * 主动发起的回复任务也纳入 currentReplyJob，
     * 用户发新消息时会取消正在进行的主动发起剩余条目。
     */
    fun agentInitiate(topicHint: String = "") {
        currentReplyJobRef.get()?.cancel()
        currentReplyJobRef.set(scope.launch {
            try {
                _isReplying.value = true
                generateAgentReply(isInitiate = true, initiateTopic = topicHint)
            } finally {
                _isReplying.value = false
                currentReplyJobRef.set(null)
            }
        })
    }

    /**
     * 触发 Onboarding 消息（Agent 主动提问了解用户）
     */
    fun triggerOnboardingMessage() {
        currentReplyJobRef.get()?.cancel()
        currentReplyJobRef.set(scope.launch {
            try {
                _isReplying.value = true
                generateAgentReply(isInitiate = true, isOnboarding = true)
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
        isInitiate: Boolean = false,
        isOnboarding: Boolean = false,
        isPendingCatchup: Boolean = false,
        isConfigMode: Boolean = false,
        continuousRound: Int = 0,
        initiateTopic: String = ""
    ) {
        try {
            // 跨天检测：确保作息是今天的（避免 App 跨天运行时基于前一天作息回复）
            com.agent.ta.service.AgentEngine.ensureTodayScheduleFresh(context)

            // 取最近 20 条消息作为上下文
            val recentMessages = chatDao.getAll().takeLast(20)
            // 构造带 [MM-DD HH:MM] 时间戳前缀的 ChatMessage
            // 让 LLM 感知对话节奏，避免跨轮次活动状态矛盾（如上一轮说"去洗澡了"，这一轮说"还在健身"）
            val timestampFormat = java.time.format.DateTimeFormatter
                .ofPattern("MM-dd HH:mm")
                .withZone(java.time.ZoneId.of("Asia/Shanghai"))
            val chatMessages = recentMessages.map { msg ->
                // 纯 emoji 消息 text=null，需要把 emoji 也带上，否则 LLM 看不到
                val rawContent = buildString {
                    if (!msg.emoji.isNullOrBlank()) append(msg.emoji)
                    if (!msg.text.isNullOrBlank()) {
                        if (isNotEmpty()) append(" ")
                        append(msg.text)
                    }
                }
                val timePrefix = try {
                    "[" + timestampFormat.format(java.time.Instant.ofEpochMilli(msg.createdAt)) + "] "
                } catch (e: Exception) {
                    ""
                }
                ChatMessage(
                    role = if (msg.direction == "inbound") "user" else "assistant",
                    content = timePrefix + rawContent
                )
            }

            // 取记忆：v2 三层记忆系统（core_memory 永驻 + memory_items 按需召回）
            val coreMemories = memoryStore.getCoreMemory()
            val recentMemoryItems = memoryStore.getRecentItems(10)
            val memories = (coreMemories + recentMemoryItems).distinctBy { it.id }
            adjustMemoryImportance(memories)

            // 获取当前活动锚点（应用侧权威状态，优先于 currentActivity）
            val activityAnchor = com.agent.ta.service.AgentEngine.getCurrentActivityAnchor()

            // 收集观察者完整快照（v2 L0 基础设施层，注入 Zone B 让 LLM 看到完整当前状态）
            // 解决 MochiBot "主回复路径错失状态" 的核心问题
            val observerSnapshots = observerRegistry.collectAll()

            // 获取历史对话摘要（v2 L2 认知层，注入 Zone B 节省 Token 保持上下文连贯）
            val currentBucketId = conversationSummarizer.getCurrentBucketId()
            val priorSummary = conversationSummarizer.getPriorSummaries(currentBucketId)

            // 构造 LLM 请求（Zone A/B/C 三段架构 + 双时间锚定 + ActivityAnchor + 观察者数据 + 对话摘要）
            val llmMessages = promptBuilder.build(
                config = configProvider.get(),
                state = com.agent.ta.service.AgentEngine.currentState.value,
                userNickname = prefs.userNickname,
                memories = memories,
                recentMessages = chatMessages,
                isOnboarding = isOnboarding,
                currentActivity = com.agent.ta.service.AgentEngine.getCurrentActivity(),
                activityAnchor = activityAnchor,
                isInitiate = isInitiate,
                initiateTopic = initiateTopic,
                todaySchedule = com.agent.ta.service.AgentEngine.getTodaySchedule(),
                isPendingCatchup = isPendingCatchup,
                isConfigMode = isConfigMode,
                continuousRound = continuousRound,
                observerSnapshots = observerSnapshots,
                conversationSummary = priorSummary
            )

            // 调 LLM（支持工具调用）+ 一致性校验重试循环
            // 校验失败时追加修正指令重试，最多 MAX_CONSISTENCY_RETRIES 次
            var reply = callLlmWithToolSupport(
                messages = llmMessages,
                isConfigMode = isConfigMode,
                isPendingCatchup = isPendingCatchup
            )
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

            // 存记忆（v2 通过 MemoryStore 统一管理，自动分级入库）
            reply.memoryUpdates.forEach { update ->
                memoryStore.addMemory(update, if (isInitiate) "event" else "chat")
            }

            // 存未来事件（LLM 从对话中提取的）
            if (reply.futureEvents.isNotEmpty()) {
                reply.futureEvents.forEach { event ->
                    futureEventDao.insert(
                        FutureEventEntity(
                            date = event.date,
                            description = event.description,
                            source = "chat"
                        )
                    )
                }
                Log.d(TAG, "已存入 ${reply.futureEvents.size} 条未来事件")
            }

            // 处理 Agent 自主作息调整（v3 事件驱动）
            // LLM 输出 scheduleAdjustment（含 adjustmentType 和参数），ScheduleAdjuster 局部修改 slots
            // 不再调 LLM 重新生成全天作息，省一次调用 + 保留已完成时段
            if (reply.scheduleAdjustment.shouldAdjust) {
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
                applyConfigUpdate(reply.configUpdate)
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
                return
            }

            Log.d(TAG, "本次回复 ${items.size} 条消息")

            // 防御性清理：从每条 replyText 中提取括号动作到 action
            // （防御 LLM 把动作写进 replyText 被读成语音）
            coroutineContext.ensureActive()  // 被取消时抛 CancellationException
            val cleanedItems = items.map { item ->
                val cleanedText = item.replyText.replace(BRACKET_REGEX, "").replace(Regex("\\s+"), " ").trim()
                val extractedAction = BRACKET_REGEX.find(item.replyText)?.groupValues?.get(1)?.trim()
                val finalAction = item.action.ifBlank { extractedAction ?: "" }
                item.copy(replyText = cleanedText, action = finalAction)
            }

            // 判断是否走"多消息独立入库"路径：
            // 条件：有意义的 replyText（长度 >= 2）至少有 2 条
            // 每条独立入库显示（像真人微信连发），只对合并文本做一次 TTS
            // 多条回复的连贯性和数量完全由 PromptBuilder 引导 LLM 生成（见 system prompt），
            // 代码层不做过滤，让 LLM 像真人一样自主决定发几条
            val effectiveReplies = cleanedItems.filter { it.replyText.length >= 2 }

            val useMultiMessageMode = effectiveReplies.size >= 2

            if (useMultiMessageMode) {
                Log.d(TAG, "多消息独立入库：${effectiveReplies.size} 条（不合并）")
                persistMultipleReplies(effectiveReplies)
                return
            }

            // 否则走原合并逻辑兜底（处理 LLM 异常输出、纯 emoji、单条 reply 等场景）
            val mergedReplyText = cleanedItems.filter { it.replyText.isNotEmpty() }.joinToString("\n") { it.replyText }
            val mergedAction = cleanedItems.firstOrNull { it.action.isNotBlank() }?.action ?: ""
            val mergedEmoji = cleanedItems.lastOrNull { it.emoji.isNotBlank() }?.emoji ?: ""
            val mergedDirectorPrompt = cleanedItems.firstOrNull { it.directorPrompt.isNotBlank() }?.directorPrompt ?: ""

            if (mergedReplyText.isBlank() && mergedEmoji.isBlank()) {
                Log.w(TAG, "LLM 重试后仍未返回有效回复")
                return
            }
            if (items.size > 1) {
                Log.d(TAG, "防御合并：${items.size} 条 → 1 条，replyText=${mergedReplyText.replace("\n", " / ")}, action=$mergedAction, emoji=$mergedEmoji")
            }

            // 纯 emoji（无文字）：不合成语音，直接入库
            if (mergedEmoji.isNotBlank() && mergedReplyText.isBlank()) {
                val emojiMsg = ChatMessageEntity(
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
                notificationHelper.notifyAgentMessage(mergedEmoji, null)
            } else {
                // 文字消息：合成语音只朗读 replyText
                var audioPath: String? = null
                var audioDurationSec: Int? = null
                if (prefs.voiceEnabled && mergedReplyText.isNotBlank()) {
                    // 合并兜底路径：取第一条 reply 的 emotion（空则 fallback neutral）
                    val mergedEmotion = cleanedItems.firstOrNull { it.emotion.isNotBlank() }?.emotion ?: ""
                    val result = synthesizeVoice(mergedReplyText, mergedDirectorPrompt, mergedEmotion)
                    audioPath = result?.first
                    audioDurationSec = result?.second
                }

                // 同时有文字和 emoji 时忽略 emoji（语音消息只保留文字，避免 emoji 挤在语音气泡里）
                val agentMsg = ChatMessageEntity(
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
                chatDao.insert(agentMsg)

                val notifyText = buildString {
                    append(mergedReplyText.replace("\n", " "))
                    if (mergedEmoji.isNotBlank()) {
                        if (isNotEmpty()) append(" ")
                        append(mergedEmoji)
                    }
                }
                notificationHelper.notifyAgentMessage(notifyText, audioPath)
            }

            // 驱动 Onboarding 推进：用户发消息后 Agent 已回复，触发下一轮或完成
            if (!isInitiate) {
                com.agent.ta.service.AgentEngine.onUserRepliedForOnboarding(context)
            }

            // 记录回复完成时间，用于连续对话检测
            lastReplyTime = System.currentTimeMillis()

        } catch (e: CancellationException) {
            // 用户发新消息，剩余条目被取消，属正常流程
            Log.d(TAG, "回复任务被取消（用户发了新消息或状态切换）")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "生成回复失败", e)
        }
    }

    /**
     * 调用 LLM，支持多轮工具调用循环（v3 function calling）
     *
     * 工具调用启用场景：
     * - 正常用户消息回复
     * - Agent 主动发起 / Onboarding
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
    private suspend fun applyConfigUpdate(update: com.agent.ta.data.remote.dto.ConfigUpdate) {
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
            ServiceLocator.agentConfigEditor.update { config ->
                val agent = config.agent
                val persona = agent.persona
                config.copy(
                    agent = agent.copy(
                        name = update.name ?: agent.name,
                        gender = update.gender ?: agent.gender,
                        age = update.age ?: agent.age,
                        persona = persona.copy(
                            background = update.background ?: persona.background,
                            personality = update.personality ?: persona.personality,
                            speakingStyle = update.speakingStyle ?: persona.speakingStyle,
                            selfNickname = update.selfNickname ?: persona.selfNickname,
                            nicknameForUser = update.nicknameForUser ?: persona.nicknameForUser,
                            relationshipToUser = update.relationshipToUser ?: persona.relationshipToUser,
                            catchphrases = update.catchphrases ?: persona.catchphrases,
                            interests = update.interests ?: persona.interests,
                            taboos = update.taboos ?: persona.taboos
                        )
                    )
                )
            }
            Log.d(TAG, "配置已更新：${update.summary}")
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
    private suspend fun persistMultipleReplies(replies: List<ReplyItem>) {
        if (replies.isEmpty()) return

        val state = com.agent.ta.service.AgentEngine.currentState.value
        val now = System.currentTimeMillis()

        // 每条独立 TTS，避免合并成长语音
        val audioResults = mutableListOf<Pair<String, Int>?>()
        if (prefs.voiceEnabled) {
            replies.forEach { reply ->
                val isPureEmoji = reply.replyText.isBlank() && reply.emoji.isNotBlank()
                if (!isPureEmoji && reply.replyText.isNotBlank()) {
                    val result = synthesizeVoice(reply.replyText, reply.directorPrompt, reply.emotion)
                    audioResults.add(result)
                } else {
                    audioResults.add(null)
                }
            }
        } else {
            replies.forEach { _ -> audioResults.add(null) }
        }

        // 每条独立入库
        replies.forEachIndexed { index, reply ->
            // 纯 emoji 消息：不合成语音
            val isPureEmoji = reply.replyText.isBlank() && reply.emoji.isNotBlank()
            // 同时有文字和 emoji：忽略 emoji（语音消息只保留文字，避免 emoji 挤在语音气泡里）
            val effectiveEmoji = if (reply.replyText.isNotBlank()) null
                                 else reply.emoji.takeIf { it.isNotBlank() }

            val audioResult = audioResults.getOrNull(index)
            val audioPath = audioResult?.first
            val audioDuration = audioResult?.second

            val msg = ChatMessageEntity(
                direction = "outbound",
                text = reply.replyText.takeIf { it.isNotBlank() },
                audioPath = audioPath,
                directorPrompt = reply.directorPrompt.takeIf { it.isNotBlank() },
                state = state.id,
                status = "sent",
                createdAt = now + index,  // 保证时间顺序
                action = reply.action.takeIf { it.isNotBlank() },
                audioDurationSec = audioDuration,
                emoji = effectiveEmoji
            )
            chatDao.insert(msg)
        }

        // 通知文案：最后一条的 replyText（最新到达视野最显眼）
        val lastReply = replies.last()
        val notifyText = buildString {
            append(lastReply.replyText)
            if (lastReply.emoji.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append(lastReply.emoji)
            }
        }
        val firstAudioPath = audioResults.firstOrNull()?.first
        notificationHelper.notifyAgentMessage(notifyText, firstAudioPath)
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
        val config = configProvider.get()
        val voiceConfig = config.voice
        val samplePath = voiceConfig.sampleFile.takeIf { it.isNotBlank() }
        // 是否配置了克隆样本（任何情绪有样本都算）
        val hasCloneSample = voiceConfig.sampleFileFor(emotion)?.isNotBlank() == true

        Log.d(TAG, "合成语音：emotion=$emotion, samplePath=$samplePath, hasCloneSample=$hasCloneSample, directorMode=${voiceConfig.directorMode}, ttsBaseUrl=${prefs.ttsBaseUrl}, ttsApiKey配置=${prefs.ttsApiKey.isNotBlank()}")

        // 第一次尝试
        var audioBytes = tryRemoteTts(text, directorPrompt, samplePath, voiceConfig, emotion)
        if (audioBytes == null) {
            // 等待后重试一次（给限流更多恢复时间）
            Log.w(TAG, "远程 TTS 第一次失败，1500ms 后重试")
            delay(1500)
            audioBytes = tryRemoteTts(text, directorPrompt, samplePath, voiceConfig, emotion)
        }

        if (audioBytes != null) {
            val audioFile = File(context.cacheDir, "voice_${UUID.randomUUID()}.wav")
            audioFile.writeBytes(audioBytes)
            val durationSec = getAudioDurationSec(audioFile.absolutePath)
            Log.d(TAG, "远程 TTS 合成成功：${audioFile.absolutePath}，${audioBytes.size} bytes，${durationSec}s")
            return Pair(audioFile.absolutePath, durationSec)
        }

        // 降级策略：配置了克隆样本时不降级系统 TTS（音色差异太大）
        if (hasCloneSample) {
            Log.w(TAG, "远程 TTS 失败，但已配置克隆样本，不降级系统 TTS（避免音色不一致）")
            return null
        }

        Log.w(TAG, "远程 TTS 两次均失败，降级系统 TTS")
        return try {
            val file = systemTtsSynthesizer.synthesize(text)
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
    ): ByteArray? {
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
    private suspend fun adjustMemoryImportance(memories: List<MemoryEntity>) {
        val now = System.currentTimeMillis()
        memories.forEach { memory ->
            // 增加访问计数
            memoryDao.incrementAccessCount(memory.id, now)
            
            // 计算新的 importance
            val daysSinceUpdate = (now - memory.updatedAt) / (1000 * 60 * 60 * 24)
            val accessBonus = memory.accessCount / 5
            val timeDecay = daysSinceUpdate / 30
            val newImportance = (memory.importance + accessBonus - timeDecay.toInt()).coerceIn(1, 5)
            
            // 如果 importance 变化超过 1，更新数据库
            if (kotlin.math.abs(newImportance - memory.importance) > 1) {
                memoryDao.updateImportance(memory.id, newImportance, now)
            }
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

        /** 连续对话超过此次数后，prompt 提示 Agent 可以主动结束对话 */
        private const val CONTINUOUS_CHAT_END_HINT = 3

        /** 上次 Agent 回复完成的时间戳（跨实例共享） */
        @Volatile
        private var lastReplyTime: Long = 0L

        /** 当前连续对话轮次（每轮 = 用户发 + Agent 回），跨实例共享 */
        @Volatile
        private var continuousRound: Int = 0
    }
}
