package com.agent.ta.domain.tool

import android.content.Context
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.data.remote.dto.ChatMessage
import kotlinx.coroutines.CoroutineScope

/**
 * Agent 工具接口（v3 通用工具系统）
 *
 * 设计哲学：
 * - 所有 Agent 能做的事都通过 Tool 接口暴露
 * - LLM 通过 function calling 自主决策是否调用
 * - 内置工具 + 自定义工具 + 第三方 Skill 统一接口
 *
 * 实现示例：
 * - WebSearchTool：联网搜索
 * - WeatherTool：天气查询
 * - TimeTool：当前时间
 * - MemoryQueryTool：查询记忆
 * - ScheduleTool：查询/调整作息
 * - TodoTool：待办管理
 *
 * 工具执行流程：
 * 1. LLM 输出 tool_calls
 * 2. ChatInteractor 调用 ToolRegistry.get(name).execute(params, context)
 * 3. 收集所有 ToolResult，作为 tool 角色消息回传给 LLM
 * 4. LLM 基于结果生成最终回复
 */
interface AgentTool {
    /** 工具名（LLM 调用用，必须唯一） */
    val name: String

    /** 给 LLM 看的描述（决定 LLM 何时调用此工具） */
    val description: String

    /**
     * JSON Schema 参数定义
     *
     * 使用 kotlinx.serialization.json.JsonElement 表示，由具体工具自行构建
     * 示例：
     * ```
     * JsonObject(mapOf(
     *     "type" to JsonPrimitive("object"),
     *     "properties" to JsonObject(mapOf(
     *         "query" to JsonObject(mapOf(
     *             "type" to JsonPrimitive("string"),
     *             "description" to JsonPrimitive("搜索关键词")
     *         ))
     *     )),
     *     "required" to JsonArray(listOf(JsonPrimitive("query")))
     * ))
     * ```
     */
    val parameters: kotlinx.serialization.json.JsonElement

    /**
     * 执行工具
     *
     * @param params LLM 输出的参数（JSON 字符串，由工具自行解析）
     * @param context 工具执行上下文（含 AgentConfig、用户消息、应用 Context 等）
     * @return 执行结果（成功返回 content，失败返回 error message）
     */
    suspend fun execute(params: String, context: ToolContext): ToolResult
}

/**
 * 工具执行上下文
 *
 * 提供工具执行所需的全部环境信息
 */
data class ToolContext(
    val agentConfig: AgentConfig,
    val userMessage: String,
    val conversationHistory: List<ChatMessage>,
    val appContext: Context,
    val scope: CoroutineScope
)

/**
 * 工具执行结果
 */
sealed class ToolResult {
    /**
     * 成功：返回内容给 LLM
     *
     * @param content 工具执行结果文本（LLM 据此生成回复）
     * @param metadata 额外元数据（可选，不传给 LLM）
     */
    data class Success(
        val content: String,
        val metadata: Map<String, Any> = emptyMap()
    ) : ToolResult()

    /**
     * 失败：返回错误信息
     *
     * @param message 错误描述（会传给 LLM，让它知道工具失败了）
     * @param retryable 是否可重试（true 时 LLM 可能会重新调用）
     */
    data class Error(
        val message: String,
        val retryable: Boolean = false
    ) : ToolResult()
}
