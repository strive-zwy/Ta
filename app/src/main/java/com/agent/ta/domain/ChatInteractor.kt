package com.agent.ta.domain

import android.content.Context
import android.util.Log
import com.agent.ta.data.local.entity.ChatMessageEntity
import com.agent.ta.data.local.entity.FutureEventEntity
import com.agent.ta.data.local.entity.MemoryEntity
import com.agent.ta.data.model.AgentState
import com.agent.ta.data.remote.dto.ChatMessage
import com.agent.ta.data.remote.dto.ReplyItem
import com.agent.ta.di.ServiceLocator
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
    private val chatDao = ServiceLocator.chatMessageDao
    private val memoryDao = ServiceLocator.memoryDao
    private val futureEventDao = ServiceLocator.futureEventDao
    private val prefs = ServiceLocator.userPreferences
    private val configProvider = ServiceLocator.agentConfigProvider
    private val notificationHelper = NotificationHelper(context)
    private val scheduleAdjuster = ScheduleAdjuster()
    private val systemTtsSynthesizer = SystemTtsSynthesizer(context)

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
            // 3. 用户消息入库
            val userMsg = ChatMessageEntity(
                direction = "inbound",
                text = text,
                audioPath = null,
                directorPrompt = null,
                state = com.agent.ta.service.AgentEngine.currentState.value.id,
                status = "received",
                createdAt = System.currentTimeMillis()
            )
            val msgId = chatDao.insert(userMsg)

            // 4. 判定当前状态
            val state = com.agent.ta.service.AgentEngine.currentState.value

            if (state == AgentState.UNAVAILABLE) {
                // 不可回复，标记为待回复（由 StateMachine 状态切换后触发 processPendingReplies）
                chatDao.updateStatus(msgId, "pending", null)
                return@launch
            }

            // 5. 可以回复，立即显示"正在输入中"（包括延迟期间，更像真人）
            _isReplying.value = true

            try {
                // 6. 等待延迟后生成回复
                // Admin v2: 优先使用 typing_indicator_duration[state.id] 作为"正在输入"显示时长
                // 没有配置则回退到 replyDelaySec
                val delaySec = resolveTypingDelaySec(state)
                delay(delaySec * 1000)
                generateAgentReply(isConfigMode = configMode.value)
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
     * 主动发起的回复任务也纳入 currentReplyJob，
     * 用户发新消息时会取消正在进行的主动发起剩余条目。
     */
    fun agentInitiate() {
        currentReplyJobRef.get()?.cancel()
        currentReplyJobRef.set(scope.launch {
            try {
                _isReplying.value = true
                generateAgentReply(isInitiate = true)
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
        isConfigMode: Boolean = false
    ) {
        try {
            // 取最近 20 条消息作为上下文
            val recentMessages = chatDao.getAll().takeLast(20)
            val chatMessages = recentMessages.map { msg ->
                // 纯 emoji 消息 text=null，需要把 emoji 也带上，否则 LLM 看不到
                val content = buildString {
                    if (!msg.emoji.isNullOrBlank()) append(msg.emoji)
                    if (!msg.text.isNullOrBlank()) {
                        if (isNotEmpty()) append(" ")
                        append(msg.text)
                    }
                }
                ChatMessage(
                    role = if (msg.direction == "inbound") "user" else "assistant",
                    content = content
                )
            }

            // 取记忆并动态调整重要性
            val memories = memoryDao.getTopMemories(20)
            adjustMemoryImportance(memories)

            // 构造 LLM 请求
            val llmMessages = promptBuilder.build(
                config = configProvider.get(),
                state = com.agent.ta.service.AgentEngine.currentState.value,
                userNickname = prefs.userNickname,
                memories = memories,
                recentMessages = chatMessages,
                isOnboarding = isOnboarding,
                currentActivity = com.agent.ta.service.AgentEngine.getCurrentActivity(),
                isInitiate = isInitiate,
                todaySchedule = com.agent.ta.service.AgentEngine.getTodaySchedule(),
                isPendingCatchup = isPendingCatchup,
                isConfigMode = isConfigMode
            )

            // 调 LLM
            val reply = llmClient.chat(llmMessages)

            // 存记忆
            reply.memoryUpdates.forEach { update ->
                memoryDao.insert(
                    MemoryEntity(
                        type = update.type,
                        category = update.category,
                        content = update.content,
                        importance = update.importance,
                        source = if (isInitiate) "event" else "chat",
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
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

            // 处理 Agent 自主作息调整
            // LLM 通过 scheduleAdjustment.shouldAdjust 自主判断（撒娇陪伴、推迟洗澡等场景）
            // 当前时段不调整，避免打乱正在进行的活动
            if (reply.scheduleAdjustment.shouldAdjust) {
                val config = configProvider.get()
                Log.d(TAG, "Agent 决定调整后续作息：${reply.scheduleAdjustment.reason}")
                com.agent.ta.service.AgentEngine.adjustSchedule(
                    context,
                    config,
                    reply.scheduleAdjustment.reason
                )
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
            // 条件：所有条目都有有意义的 replyText（长度 >= 2）且至少有 2 条
            // 这种情况下每条独立入库显示（像真人微信连发），只对合并文本做一次 TTS
            val meaningfulReplies = cleanedItems.filter { it.replyText.length >= 2 }
            val useMultiMessageMode = meaningfulReplies.size >= 2

            if (useMultiMessageMode) {
                Log.d(TAG, "多消息独立入库：${meaningfulReplies.size} 条（不合并）")
                persistMultipleReplies(meaningfulReplies)
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

        } catch (e: CancellationException) {
            // 用户发新消息，剩余条目被取消，属正常流程
            Log.d(TAG, "回复任务被取消（用户发了新消息或状态切换）")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "生成回复失败", e)
        }
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
     * 多消息独立入库：每条 reply 作为独立消息显示（像真人微信连发）
     *
     * TTS 策略：
     * - 第一条做 TTS 合成（合并所有 replyText 朗读一次作为"语音版总回复"）
     * - 其他条纯文字显示，不带 audioPath
     *
     * 这样平衡了：
     * - 视觉：用户看到 N 条独立消息，每条都得到回应（解决"消息没回复"问题）
     * - 语音：避免 N 次 TTS 调用导致的延迟和成本
     */
    private suspend fun persistMultipleReplies(replies: List<ReplyItem>) {
        if (replies.isEmpty()) return

        val state = com.agent.ta.service.AgentEngine.currentState.value
        val now = System.currentTimeMillis()

        // 第一条做 TTS（合并所有 replyText 朗读一次）
        val firstReply = replies.first()
        val allTextJoined = replies.joinToString("\n") { it.replyText }
        var firstAudioPath: String? = null
        var firstAudioDuration: Int? = null
        if (prefs.voiceEnabled && allTextJoined.isNotBlank()) {
            val result = synthesizeVoice(allTextJoined, firstReply.directorPrompt, firstReply.emotion)
            firstAudioPath = result?.first
            firstAudioDuration = result?.second
        }

        // 每条独立入库；同时有 replyText 和 emoji 时忽略 emoji（避免语音气泡里塞 emoji 显示难看）
        replies.forEachIndexed { index, reply ->
            val isFirst = index == 0
            val isLast = index == replies.size - 1

            // 纯 emoji 消息：不合成语音
            val isPureEmoji = reply.replyText.isBlank() && reply.emoji.isNotBlank()
            // 同时有文字和 emoji：忽略 emoji（语音消息只保留文字，避免 emoji 挤在语音气泡里）
            val effectiveEmoji = if (reply.replyText.isNotBlank()) null
                                 else reply.emoji.takeIf { it.isNotBlank() }

            val audioPath = if (isFirst && !isPureEmoji) firstAudioPath else null
            val audioDuration = if (isFirst && !isPureEmoji) firstAudioDuration else null

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
     * 失败重试：远程 TTS 失败时短暂等待后重试一次（避免连续调用被限流导致中间几条降级系统 TTS）
     */
    private suspend fun synthesizeVoice(text: String, directorPrompt: String, emotion: String): Pair<String, Int>? {
        val config = configProvider.get()
        val voiceConfig = config.voice
        val samplePath = voiceConfig.sampleFile.takeIf { it.isNotBlank() }

        Log.d(TAG, "合成语音：emotion=$emotion, samplePath=$samplePath, directorMode=${voiceConfig.directorMode}, ttsBaseUrl=${prefs.ttsBaseUrl}, ttsApiKey配置=${prefs.ttsApiKey.isNotBlank()}")

        // 第一次尝试
        var audioBytes = tryRemoteTts(text, directorPrompt, samplePath, voiceConfig, emotion)
        if (audioBytes == null) {
            // 短暂等待后重试一次（可能因限流或瞬时网络抖动失败）
            Log.w(TAG, "远程 TTS 第一次失败，500ms 后重试")
            delay(500)
            audioBytes = tryRemoteTts(text, directorPrompt, samplePath, voiceConfig, emotion)
        }

        if (audioBytes != null) {
            val audioFile = File(context.cacheDir, "voice_${UUID.randomUUID()}.wav")
            audioFile.writeBytes(audioBytes)
            val durationSec = getAudioDurationSec(audioFile.absolutePath)
            Log.d(TAG, "远程 TTS 合成成功：${audioFile.absolutePath}，${audioBytes.size} bytes，${durationSec}s")
            return Pair(audioFile.absolutePath, durationSec)
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
    }
}
