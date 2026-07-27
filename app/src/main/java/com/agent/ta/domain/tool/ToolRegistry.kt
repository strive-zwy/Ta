package com.agent.ta.domain.tool

import android.util.Log
import com.agent.ta.data.remote.dto.FunctionDefinition
import com.agent.ta.data.remote.dto.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * 工具注册中心（v3 通用工具系统）
 *
 * 职责：
 * 1. 注册内置工具和自定义工具
 * 2. 按工具名查找工具实例
 * 3. 收集所有启用工具的 ToolDefinition（传给 LLM）
 * 4. 批量执行工具调用（并行 + 超时控制）
 *
 * 使用方式：
 * ```
 * val registry = ToolRegistry()
 * registry.register(WebSearchTool())
 * registry.register(WeatherTool())
 *
 * // 传给 LLM
 * val tools = registry.getAllDefinitions()
 *
 * // 执行 LLM 输出的 tool_calls
 * val results = registry.executeToolCalls(toolCalls, context)
 * ```
 *
 * 全局单例通过 ServiceLocator 获取，避免重复创建
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, AgentTool>()
    private val json = Json { ignoreUnknownKeys = true }

    /** 注册工具 */
    fun register(tool: AgentTool) {
        tools[tool.name] = tool
        Log.d(TAG, "已注册工具: ${tool.name}")
    }

    /** 按名称查找工具 */
    fun get(name: String): AgentTool? = tools[name]

    /** 获取所有已注册工具 */
    fun getAll(): List<AgentTool> = tools.values.toList()

    /** 是否有工具已注册 */
    fun hasTools(): Boolean = tools.isNotEmpty()

    /**
     * 收集所有工具的 ToolDefinition（传给 LLM 的 tools 参数）
     */
    fun getAllDefinitions(): List<ToolDefinition> {
        return tools.values.map { tool ->
            ToolDefinition(
                function = FunctionDefinition(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters
                )
            )
        }
    }

    /**
     * 批量执行工具调用（并行）
     *
     * @param toolCalls LLM 输出的工具调用列表
     * @param context 工具执行上下文
     * @return 每个工具调用的执行结果（按 toolCall.id 索引）
     *
     * 设计要点：
     * - 并行执行多个工具（async + awaitAll）
     * - 单个工具超时 30 秒（withTimeout）
     * - 单个工具失败不影响其他工具
     * - 返回结果包含 toolCallId，方便回传给 LLM
     */
    suspend fun executeToolCalls(
        toolCalls: List<com.agent.ta.data.remote.dto.ToolCall>,
        context: ToolContext
    ): List<ToolCallResult> {
        if (toolCalls.isEmpty()) return emptyList()

        return toolCalls.map { call ->
            context.scope.async {
                executeSingleToolCall(call, context)
            }
        }.awaitAll()
    }

    private suspend fun executeSingleToolCall(
        call: com.agent.ta.data.remote.dto.ToolCall,
        context: ToolContext
    ): ToolCallResult {
        val tool = get(call.function.name)
        if (tool == null) {
            return ToolCallResult(
                toolCallId = call.id,
                toolName = call.function.name,
                result = ToolResult.Error("工具不存在: ${call.function.name}", retryable = false)
            )
        }

        return try {
            // 30 秒超时
            val result = withTimeout(30_000) {
                tool.execute(call.function.arguments, context)
            }
            ToolCallResult(
                toolCallId = call.id,
                toolName = call.function.name,
                result = result
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "工具 ${call.function.name} 执行失败", e)
            ToolCallResult(
                toolCallId = call.id,
                toolName = call.function.name,
                result = ToolResult.Error(
                    message = "工具执行失败: ${e.message ?: "未知错误"}",
                    retryable = true
                )
            )
        }
    }

    companion object {
        private const val TAG = "ToolRegistry"
    }
}

/**
 * 单个工具调用的执行结果
 */
data class ToolCallResult(
    val toolCallId: String,
    val toolName: String,
    val result: ToolResult
) {
    /** 转换为 tool 角色消息的 content */
    fun toMessageContent(): String = when (result) {
        is ToolResult.Success -> result.content
        is ToolResult.Error -> "工具执行失败: ${result.message}"
    }
}
