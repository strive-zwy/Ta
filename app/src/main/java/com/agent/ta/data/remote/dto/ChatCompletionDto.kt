package com.agent.ta.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI 兼容 Chat Completion 请求
 *
 * v3 新增 tools 字段支持 function calling：
 * - LLM 可以决定调用工具而非直接回复
 * - App 端执行工具后将结果作为 tool 角色消息回传
 * - 支持多轮工具调用（最多 3 轮防死循环）
 */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.85,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /** 工具定义（function calling） */
    val tools: List<ToolDefinition>? = null,
    /** 强制工具调用模式：auto（默认）/ none / 指定工具名 */
    @SerialName("tool_choice") val toolChoice: String? = null
)

/**
 * 工具定义（OpenAI function calling 格式）
 */
@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonElement
)

/**
 * 聊天消息
 *
 * v3 扩展支持 function calling：
 * - tool_calls：assistant 消息中 LLM 决定调用的工具列表
 * - tool_call_id：tool 角色消息关联的 tool_call ID
 * - name：tool 角色消息的工具名
 *
 * 向后兼容：所有新字段都是可选的，旧代码无需修改
 */
@Serializable
data class ChatMessage(
    val role: String,        // system | user | assistant | tool
    val content: String = "",
    /** assistant 消息：LLM 决定调用的工具列表 */
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    /** tool 角色消息：关联的 tool_call ID */
    @SerialName("tool_call_id") val toolCallId: String? = null,
    /** tool 角色消息：工具名 */
    val name: String? = null
)

/**
 * 工具调用（LLM 输出）
 */
@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

/**
 * 函数调用（LLM 输出的参数）
 */
@Serializable
data class FunctionCall(
    val name: String,
    /** JSON 字符串格式的参数 */
    val arguments: String
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList()
)

@Serializable
data class Choice(
    val message: ChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)
