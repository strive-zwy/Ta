package com.agent.ta.data.remote

import android.util.Log
import com.agent.ta.data.remote.dto.ChatCompletionRequest
import com.agent.ta.data.remote.dto.ChatMessage
import com.agent.ta.data.remote.dto.AgentReply
import com.agent.ta.data.remote.dto.ReplyItem
import com.agent.ta.data.remote.dto.ToolCall
import com.agent.ta.data.remote.dto.ToolDefinition
import com.agent.ta.data.remote.api.LlmApi
import com.agent.ta.di.ServiceLocator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

data class LlmDiagnosisResult(
    val success: Boolean,
    val reply: String,
    val elapsedMs: Long,
    val message: String
) {
    companion object {
        fun fromReply(reply: String, elapsedMs: Long): LlmDiagnosisResult {
            val trimmed = reply.trim()
            return LlmDiagnosisResult(
                success = trimmed.isNotBlank(),
                reply = trimmed,
                elapsedMs = elapsedMs,
                message = if (trimmed.isNotBlank()) "模型回复正常" else "模型返回了空内容"
            )
        }

        fun failure(message: String, elapsedMs: Long): LlmDiagnosisResult =
            LlmDiagnosisResult(false, "", elapsedMs, message)
    }
}

/**
 * LLM 客户端（OpenAI 兼容协议）
 * 要求 LLM 输出 JSON 结构（replies 数组 + action + directorPrompt + memoryUpdates + futureEvents）
 *
 * v3 新增 function calling 支持：
 * - chatWithTools：带工具定义的对话，LLM 可选择调用工具或直接回复
 * - 工具调用结果通过 ToolCallResponse 返回，由 ChatInteractor 执行后回传
 */
class LlmClient {
    private val prefs = ServiceLocator.userPreferences
    private val json = ApiClientFactory.json

    /**
     * 工具调用响应（v3 function calling）
     *
     * LLM 收到带 tools 的请求后，返回两种结果之一：
     * - Reply：LLM 直接给出最终回复（未调用工具，或在工具调用后已生成最终回复）
     * - ToolCalls：LLM 决定调用工具，需 App 执行后回传结果
     *
     * ChatInteractor 中的循环：
     * ```
     * repeat(3) {
     *     when (val resp = llmClient.chatWithTools(messages, tools)) {
     *         is ToolCallResponse.Reply -> return resp.reply  // 最终回复
     *         is ToolCallResponse.ToolCalls -> {
     *             messages = messages + resp.assistantMessage
     *             val results = toolRegistry.executeToolCalls(resp.toolCalls, context)
     *             messages = messages + results.map { it.toToolMessage() }
     *         }
     *     }
     * }
     * ```
     */
    sealed class ToolCallResponse {
        /** LLM 直接回复（含 AgentReply 结构化结果） */
        data class Reply(val reply: com.agent.ta.data.remote.dto.AgentReply) : ToolCallResponse()

        /**
         * LLM 决定调用工具
         *
         * @param toolCalls LLM 输出的工具调用列表
         * @param assistantMessage 含 tool_calls 的 assistant 消息（需原样回传给 LLM）
         */
        data class ToolCalls(
            val toolCalls: List<com.agent.ta.data.remote.dto.ToolCall>,
            val assistantMessage: ChatMessage
        ) : ToolCallResponse()
    }

    // 每次调用时根据当前 baseUrl 创建 API 实例，避免用户修改配置后不生效
    private fun getApi(): LlmApi {
        return ApiClientFactory.createLlmApi(prefs.llmBaseUrl)
    }

    /**
     * 清理 API Key：去掉所有空白字符（含换行符/制表符）
     *
     * 用户从配置文件粘贴 apiKey 时可能带入换行符（0x0a），
     * 会导致 Authorization header 非法（"Unexpected char 0x0a"）
     */
    private fun cleanApiKey(): String = prefs.llmApiKey.filter { !it.isWhitespace() }

    suspend fun diagnose(
        baseUrl: String,
        apiKey: String,
        model: String
    ): LlmDiagnosisResult {
        val startedAt = System.currentTimeMillis()
        return try {
            val response = ApiClientFactory.createLlmApi(baseUrl.trim()).chatCompletion(
                auth = "Bearer ${apiKey.filter { !it.isWhitespace() }}",
                request = ChatCompletionRequest(
                    model = model.trim(),
                    messages = listOf(ChatMessage(role = "user", content = "hello")),
                    temperature = 0.0
                )
            )
            LlmDiagnosisResult.fromReply(
                response.choices.firstOrNull()?.message?.content.orEmpty(),
                System.currentTimeMillis() - startedAt
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LlmDiagnosisResult.failure(
                message = e.message?.takeIf { it.isNotBlank() }
                    ?: e.javaClass.simpleName,
                elapsedMs = System.currentTimeMillis() - startedAt
            )
        }
    }

    /**
     * 发起对话，返回结构化回复
     * 失败时自动重试，最多重试 3 次，延迟递增（指数退避）
     *
     * 重试策略注意：
     * - 重试消息不能强制"恰好 1 个 reply"，否则会覆盖 PromptBuilder 的多消息引导
     *   （连发场景下 PromptBuilder 要求 N 条 replies，强制 1 条会让前面的用户消息被忽略）
     */
    suspend fun chat(messages: List<ChatMessage>): AgentReply {
        val maxRetries = 3
        val baseDelayMs = 800L

        // 第一次尝试
        val firstAttempt = requestReplyOrNull(messages, temperature = 0.85)
        if (firstAttempt?.hasContent() == true) {
            Log.d(TAG, "首次回复有效：replies=${firstAttempt.replies.size}, textLength=${firstAttempt.replyText.length}")
            return firstAttempt
        }
        Log.w(TAG, "首次回复无效，开始重试。firstAttempt=${firstAttempt == null}, hasContent=${firstAttempt?.hasContent()}")

        // 重试逻辑：指数退避
        var lastResult: AgentReply? = firstAttempt
        for (retry in 1..maxRetries) {
            val delayMs = baseDelayMs * (1L shl (retry - 1))  // 800, 1600, 3200
            delay(delayMs)

            // 重试消息：保持原有 replies 数量（不强制 1 条），只纠正格式问题
            val retryMessages = messages + ChatMessage(
                role = "system",
                content = "上一轮回复无效或格式错误。请重新生成，只返回一个合法 JSON 对象。" +
                    "保持原有的 replies 数组长度（不要合并也不要拆分）；" +
                    "replyText 只写对话内容，禁止包含括号动作（括号内容请放到 action 字段）；" +
                    "action 单独填写（第三人称旁白）；" +
                    "至少 replyText 或 emoji 有一个非空；" +
                    "不要输出 Markdown 代码块（不要 ```json 包裹）或任何额外文字。"
            )
            val result = requestReplyOrNull(retryMessages, temperature = 0.5)
            if (result?.hasContent() == true) {
                Log.d(TAG, "第 $retry 次重试成功：replies=${result.replies.size}, replyText=${result.replyText.take(30)}")
                return result
            }
            Log.w(TAG, "第 $retry 次重试仍无效")
            lastResult = result
        }

        Log.w(TAG, "重试全部失败，返回最后结果")
        return lastResult ?: AgentReply()
    }

    /**
     * 带工具定义的对话（v3 function calling）
     *
     * LLM 可以：
     * 1. 直接回复：返回 ToolCallResponse.Reply（含 AgentReply）
     * 2. 调用工具：返回 ToolCallResponse.ToolCalls（含 toolCalls 列表）
     *
     * 调用流程（在 ChatInteractor 中循环）：
     * ```
     * var messages = initialMessages
     * repeat(3) {  // 最多 3 轮，防死循环
     *     when (val resp = llmClient.chatWithTools(messages, tools)) {
     *         is ToolCallResponse.Reply -> return resp.reply  // 拿到最终回复
     *         is ToolCallResponse.ToolCalls -> {
     *             messages = messages + resp.assistantMessage  // 加入 LLM 的 tool_calls 消息
     *             val results = toolRegistry.executeToolCalls(resp.toolCalls, context)
     *             messages = messages + results.map { it.toToolMessage() }  // 加入工具结果
     *         }
     *     }
     * }
     * ```
     *
     * @param messages 对话历史（含 system prompt）
     * @param tools 工具定义列表（来自 ToolRegistry.getAllDefinitions()）
     * @return ToolCallResponse.Reply（直接回复）或 ToolCallResponse.ToolCalls（需要执行工具）
     */
    suspend fun chatWithTools(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): ToolCallResponse {
        val apiKey = cleanApiKey()
        val model = prefs.llmModel
        Log.d(TAG, "chatWithTools: model=$model, tools=${tools.size}, messages=${messages.size}")

        val request = ChatCompletionRequest(
            model = model,
            messages = messages,
            temperature = 0.85,
            tools = tools,
            toolChoice = "auto"
        )

        return try {
            val response = getApi().chatCompletion(
                auth = "Bearer $apiKey",
                request = request
            )
            val choice = response.choices.firstOrNull()
            val message = choice?.message

            // 检查是否有 tool_calls
            val toolCalls = message?.toolCalls
            if (!toolCalls.isNullOrEmpty()) {
                Log.d(TAG, "LLM 决定调用 ${toolCalls.size} 个工具: ${toolCalls.joinToString { it.function.name }}")
                // 返回 assistant 消息（含 tool_calls）+ 工具调用列表
                ToolCallResponse.ToolCalls(
                    toolCalls = toolCalls,
                    assistantMessage = ChatMessage(
                        role = "assistant",
                        content = message.content ?: "",
                        toolCalls = toolCalls
                    )
                )
            } else {
                // 直接回复，解析为 AgentReply
                val content = message?.content.orEmpty().trim()
                if (content.isBlank()) {
                    Log.w(TAG, "chatWithTools: LLM 返回空内容")
                    ToolCallResponse.Reply(AgentReply())
                } else {
                    Log.d(TAG, "chatWithTools: LLM 直接回复，contentLength=${content.length}")
                    ToolCallResponse.Reply(parseReply(content))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "chatWithTools 请求异常", e)
            // 失败时回退到无工具调用，避免工具系统故障阻塞对话
            Log.w(TAG, "chatWithTools 失败，回退到普通 chat")
            val reply = chat(messages)
            ToolCallResponse.Reply(reply)
        }
    }

    private suspend fun requestReplyOrNull(
        messages: List<ChatMessage>,
        temperature: Double
    ): AgentReply? {
        return try {
            requestReply(messages, temperature)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "LLM 请求异常", e)
            null
        }
    }

    private suspend fun requestReply(messages: List<ChatMessage>, temperature: Double): AgentReply {
        val apiKey = cleanApiKey()
        val baseUrl = prefs.llmBaseUrl
        val model = prefs.llmModel
        Log.d(TAG, "请求配置：baseUrl=$baseUrl, model=$model, apiKey长度=${apiKey.length}")

        val request = ChatCompletionRequest(
            model = model,
            messages = messages,
            temperature = temperature
        )

        val response = getApi().chatCompletion(
            auth = "Bearer $apiKey",
            request = request
        )
        val content = response.choices.firstOrNull()?.message?.content.orEmpty().trim()
        if (content.isBlank()) {
            Log.w(TAG, "LLM 返回空内容")
            return AgentReply()
        }
        Log.d(TAG, "LLM 返回成功，contentLength=${content.length}")
        return parseReply(content)
    }

    /**
     * 判断回复是否有有效内容
     *
     * 注意：不要预先 strip 括号动作。原因：
     * - ChatInteractor 的防御性清理已经处理"括号内容提取到 action + 从 replyText 删除"
     * - 如果 LLM 返回 "（笑）你好啊"，这里判定为有效（replyText 非空），
     *   ChatInteractor 会把它拆分为 replyText="你好啊" + action="笑"
     * - 不要在 LlmClient 层重复 strip，否则会把"（笑）你好啊"误判为空 → 触发不必要的重试
     */
    private fun AgentReply.hasContent(): Boolean {
        // 顶层 replyText（旧格式）非空即有效
        if (replyText.isNotBlank()) return true
        // replies 数组（新格式）：任一 reply 有非空 replyText 或 emoji 即有效
        return replies.any {
            it.replyText.isNotBlank() || it.emoji.isNotBlank()
        }
    }

    /**
     * 发起对话，返回原始文本内容（供 DailyPlanner 等需要自定义解析的场景使用）
     */
    suspend fun chatRaw(messages: List<ChatMessage>): String {
        val request = ChatCompletionRequest(
            model = prefs.llmModel,
            messages = messages,
            temperature = 0.85
        )

        val response = getApi().chatCompletion(
            auth = "Bearer ${cleanApiKey()}",
            request = request
        )

        return response.choices.firstOrNull()?.message?.content ?: ""
    }

    /**
     * 解析 LLM 输出为结构化回复
     * 支持新格式（replies 数组）和旧格式（单条 replyText）+ 纯文本
     *
     * 容错处理：
     * - 剥离 Markdown 代码块包裹（```json ... ```），LLM 偶尔会忽略"不要 Markdown"的指令
     * - 抽取 JSON 对象主体（避免前后有解释性文字导致解析失败）
     * - JSON 解析失败时，把纯文本作为 replyText 兜底返回（避免不必要的重试）
     *   LLM 经常无视"只返回 JSON"的指令直接输出对话文本（如 "😄 在呢在呢，你说～"），
     *   这种情况下重试也大概率返回纯文本，不如直接用第一次的结果，节省 6 秒+ 重试时间。
     */
    private fun parseReply(content: String): AgentReply {
        return try {
            // 1. 剥离 Markdown 代码块（```json ... ``` 或 ``` ... ```）
            val stripped = stripMarkdownCodeFence(content)
            // 2. 抽取最外层 JSON 对象（避免前后有解释性文字）
            val jsonStr = extractJsonObject(stripped) ?: stripped
            val obj = json.parseToJsonElement(jsonStr).jsonObject

            // 优先解析 replies 数组（新格式）
            val repliesArr = obj["replies"]
            val replies = if (repliesArr is JsonArray) {
                repliesArr.mapNotNull { item ->
                    val itemObj = item as? JsonObject ?: return@mapNotNull null
                    val text = itemObj["replyText"]?.jsonPrimitive?.contentOrNull ?: ""
                    val emoji = itemObj["emoji"]?.jsonPrimitive?.contentOrNull ?: ""
                    // 两个都空则跳过；有 emoji 即使 text 为空也保留（纯表情消息）
                    if (text.isBlank() && emoji.isBlank()) return@mapNotNull null
                    ReplyItem(
                        replyText = text,
                        action = itemObj["action"]?.jsonPrimitive?.contentOrNull ?: "",
                        directorPrompt = itemObj["directorPrompt"]?.jsonPrimitive?.contentOrNull ?: "",
                        emoji = emoji,
                        emotion = itemObj["emotion"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else emptyList()

            if (replies.isNotEmpty()) {
                AgentReply(
                    replies = replies,
                    scheduleAdjustment = parseScheduleAdjustment(obj),
                    memoryUpdates = parseMemoryUpdates(obj),
                    futureEvents = parseFutureEvents(obj),
                    commitments = parseCommitments(obj),
                    commitmentUpdates = parseCommitmentUpdates(obj),
                    milestoneDeclared = parseMilestoneDeclared(obj),
                    emotionIntensity = parseEmotionIntensity(obj),
                    wantAvatarId = parseWantAvatarId(obj),
                    firstMeetingMeta = parseFirstMeetingMeta(obj),
                    nicknameResolution = parseNicknameResolution(obj)
                )
            } else {
                // 旧格式 fallback：单条 replyText + action
                AgentReply(
                    replyText = obj["replyText"]?.jsonPrimitive?.contentOrNull ?: "",
                    action = obj["action"]?.jsonPrimitive?.contentOrNull ?: "",
                    directorPrompt = obj["directorPrompt"]?.jsonPrimitive?.contentOrNull ?: "",
                    scheduleAdjustment = parseScheduleAdjustment(obj),
                    memoryUpdates = parseMemoryUpdates(obj),
                    futureEvents = parseFutureEvents(obj),
                    commitments = parseCommitments(obj),
                    commitmentUpdates = parseCommitmentUpdates(obj),
                    milestoneDeclared = parseMilestoneDeclared(obj),
                    emotionIntensity = parseEmotionIntensity(obj),
                    wantAvatarId = parseWantAvatarId(obj),
                    firstMeetingMeta = parseFirstMeetingMeta(obj),
                    nicknameResolution = parseNicknameResolution(obj)
                )
            }
        } catch (e: Exception) {
            // JSON 解析失败：LLM 返回了纯文本（无视了"只返回 JSON"的指令）
            // 直接把纯文本作为 replyText 兜底返回，避免触发不必要的重试
            // （重试也大概率返回纯文本，白白浪费 6 秒+ 和 3 次 LLM 调用）
            val text = content.trim()
            if (text.isNotBlank()) {
                Log.w(TAG, "LLM 未返回 JSON 格式，将纯文本作为回复兜底：${text.take(80)}")
                AgentReply(replyText = text)
            } else {
                Log.w(TAG, "LLM 返回空内容")
                AgentReply()
            }
        }
    }

    /**
     * 剥离 Markdown 代码块包裹：
     * - ```json\n{...}\n``` → {...}
     * - ```\n{...}\n``` → {...}
     * 如果没有代码块包裹，原样返回
     */
    private fun stripMarkdownCodeFence(content: String): String {
        val trimmed = content.trim()
        // 匹配 ```开头 + 可选语言标识 + 内容 + ```结尾
        val fencePattern = Regex("""^```[a-zA-Z]*\s*\n([\s\S]*?)\n\s*```$""")
        val match = fencePattern.matchEntire(trimmed)
        if (match != null) {
            val inner = match.groupValues[1].trim()
            Log.d(TAG, "剥离 Markdown 代码块包裹：${trimmed.length} → ${inner.length} 字符")
            return inner
        }
        // 容错：只有开头 ``` 没有结尾的情况
        if (trimmed.startsWith("```")) {
            val afterFence = trimmed.substringAfter("```").substringAfter("\n", "").trim()
            if (afterFence.isNotEmpty()) return afterFence
        }
        return trimmed
    }

    /**
     * 从文本中抽取最外层 JSON 对象主体
     * 用于处理 LLM 在 JSON 前后加了解释性文字的情况（如"好的，这是回复："）
     * 通过花括号配对找到第一个完整的 {...} 块
     */
    private fun extractJsonObject(content: String): String? {
        val start = content.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until content.length) {
            val c = content[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return content.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun parseMemoryUpdates(obj: JsonObject): List<com.agent.ta.data.remote.dto.MemoryUpdate> {
        return try {
            val arr = obj["memoryUpdates"] ?: return emptyList()
            json.decodeFromString(arr.toString())
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseFutureEvents(obj: JsonObject): List<com.agent.ta.data.remote.dto.FutureEventItem> {
        return try {
            val arr = obj["futureEvents"] ?: return emptyList()
            json.decodeFromString(arr.toString())
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseCommitments(obj: JsonObject): List<com.agent.ta.data.remote.dto.CommitmentItem> {
        return try {
            val arr = obj["commitments"] ?: return emptyList()
            json.decodeFromString(arr.toString())
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseCommitmentUpdates(obj: JsonObject): List<com.agent.ta.data.remote.dto.CommitmentUpdateItem> {
        return try {
            val arr = obj["commitmentUpdates"] ?: return emptyList()
            json.decodeFromString(arr.toString())
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 解析 LLM 主动声明的里程碑 type（Phase 2 关系系统）
     * 返回 null 表示本次回复未涉及关系节点
     */
    private fun parseMilestoneDeclared(obj: JsonObject): String? {
        return try {
            val value = obj["milestoneDeclared"] ?: return null
            val str = value.jsonPrimitive.contentOrNull
            // 空字符串、空白字符串、显式 "null" 都视为未声明
            if (str.isNullOrBlank() || str == "null") null else str
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 wantAvatarId 字段（Agent 自主切换头像）
     * LLM 输出希望显示的头像 id（AvatarConfig.id）。
     * 返回 null 表示本次回复不切换头像；非空字符串表示切到该 id。
     */
    private fun parseWantAvatarId(obj: JsonObject): String? {
        return try {
            val value = obj["wantAvatarId"] ?: return null
            val str = value.jsonPrimitive.contentOrNull
            if (str.isNullOrBlank() || str == "null") null else str
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 emotionIntensity 字段（Phase 3 情感势能驱动主动发起）
     * LLM 自报的情绪强度：-2.0(强烈负面) ~ +2.0(强烈兴奋)
     * 缺失或解析失败默认 0f（平静）
     */
    private fun parseEmotionIntensity(obj: JsonObject): Float {
        return try {
            val value = obj["emotionIntensity"] ?: return 0f
            // 尝试作为数字解析（JSON 中可能是 number 或 string）
            value.jsonPrimitive.contentOrNull?.toFloatOrNull() ?: 0f
        } catch (e: Exception) {
            0f
        }
    }

    /**
     * 解析 firstMeetingMeta 字段（Task 12 首次见面元数据）
     *
     * LLM 在首次见面场景输出 introducedSelf / askedForNickname 两个布尔值，
     * 用于本地校验问候是否达成两个核心目标。
     * 缺失或解析失败返回 null（普通对话场景不输出此字段）。
     */
    private fun parseFirstMeetingMeta(obj: JsonObject): com.agent.ta.data.remote.dto.FirstMeetingMeta? {
        return try {
            val metaObj = obj["firstMeetingMeta"] as? JsonObject ?: return null
            com.agent.ta.data.remote.dto.FirstMeetingMeta(
                introducedSelf = metaObj["introducedSelf"]?.jsonPrimitive?.contentOrNull == "true",
                askedForNickname = metaObj["askedForNickname"]?.jsonPrimitive?.contentOrNull == "true"
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 nicknameResolution 字段（Task 14 称呼解析）
     *
     * LLM 在首次见面 WAITING_NICKNAME / FOLLOW_UP_ASKED 阶段，以及首次见面完成后
     * 用户修改称呼时，在同一次普通回复中输出此字段。
     *
     * 缺失或解析失败返回 null（普通对话场景不输出此字段）。
     * 越界的 confidence 和未知的 intent 由 NicknameResolver.parse 做进一步清洗。
     */
    private fun parseNicknameResolution(obj: JsonObject): com.agent.ta.data.remote.dto.NicknameResolution? {
        return try {
            val resObj = obj["nicknameResolution"] as? JsonObject ?: return null
            val intent = resObj["intent"]?.jsonPrimitive?.contentOrNull ?: "NONE"
            val nickname = resObj["nickname"]?.jsonPrimitive?.contentOrNull
            val confidence = resObj["confidence"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
            val evidence = resObj["evidence"]?.jsonPrimitive?.contentOrNull ?: ""
            val shouldSave = resObj["shouldSave"]?.jsonPrimitive?.contentOrNull == "true"
            com.agent.ta.data.remote.dto.NicknameResolution(
                intent = intent,
                nickname = nickname,
                confidence = confidence,
                evidence = evidence,
                shouldSave = shouldSave
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseScheduleAdjustment(obj: JsonObject): com.agent.ta.data.remote.dto.ScheduleAdjustment {
        return try {
            val adjObj = obj["scheduleAdjustment"] as? JsonObject ?: return com.agent.ta.data.remote.dto.ScheduleAdjustment()
            com.agent.ta.data.remote.dto.ScheduleAdjustment(
                shouldAdjust = adjObj["shouldAdjust"]?.jsonPrimitive?.contentOrNull == "true",
                reason = adjObj["reason"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        } catch (e: Exception) {
            com.agent.ta.data.remote.dto.ScheduleAdjustment()
        }
    }

    companion object {
        private const val TAG = "LlmClient"
    }
}
