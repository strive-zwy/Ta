package com.agent.ta.domain.tool.builtin

import com.agent.ta.domain.tool.AgentTool
import com.agent.ta.domain.tool.ToolContext
import com.agent.ta.domain.tool.ToolResult
import com.agent.ta.di.ServiceLocator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 待办事项工具（内置）
 *
 * 让 LLM 可以从对话中提取待办事项并管理，影响后续作息安排
 *
 * 三种操作：
 * - add: 添加待办事项（LLM 在对话中识别到 todo 时调用）
 * - list: 查询待办事项列表
 * - complete: 标记完成
 *
 * 与 DailyPlanner 的联动：
 * - 待办事项存入 future_events 表
 * - DailyPlanner 生成作息时查询未来事件并注入 prompt
 * - LLM 看到待办事项后，会安排进作息中
 *
 * 调用时机：
 * - 用户说"明天记得提醒我看电影" → LLM 调 add
 * - 用户问"我还有什么待办" → LLM 调 list
 * - 用户说"那个事做完了" → LLM 调 complete
 */
class TodoTool : AgentTool {
    override val name = "manage_todo"
    override val description = "管理待办事项。从对话中提取待办事项（如用户提到「明天记得做某事」），或查询/完成待办。待办事项会影响 Agent 的作息安排。"

    override val parameters: JsonElement = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "action" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("操作类型：add（添加）/ list（查询）/ complete（完成）"),
                "enum" to JsonArray(listOf(
                    JsonPrimitive("add"), JsonPrimitive("list"), JsonPrimitive("complete")
                ))
            )),
            "date" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("待办日期 yyyy-MM-dd（add 时必填，complete 时可选）")
            )),
            "description" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("待办内容描述（add 时必填）")
            )),
            "todoId" to JsonObject(mapOf(
                "type" to JsonPrimitive("number"),
                "description" to JsonPrimitive("待办 ID（complete 时必填，从 list 结果获取）")
            ))
        )),
        "required" to JsonArray(listOf(JsonPrimitive("action")))
    ))

    private val json = Json { ignoreUnknownKeys = true }
    private val zoneId = ZoneId.of("Asia/Shanghai")

    override suspend fun execute(params: String, context: ToolContext): ToolResult {
        val action = parseField(params, "action")
            ?: return ToolResult.Error("缺少 action 参数")

        return when (action.lowercase()) {
            "add" -> addTodo(params)
            "list" -> listTodos(params)
            "complete" -> completeTodo(params)
            else -> ToolResult.Error("未知操作：$action，支持 add/list/complete")
        }
    }

    /**
     * 添加待办事项
     */
    private suspend fun addTodo(params: String): ToolResult {
        val description = parseField(params, "description")
            ?: return ToolResult.Error("add 操作缺少 description 参数")
        val date = parseField(params, "date")
            ?: LocalDate.now(zoneId).plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

        val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
        val id = ServiceLocator.futureEventDao.insert(
            com.agent.ta.data.local.entity.FutureEventEntity(
                agentId = agentId,
                date = date,
                description = description,
                source = "todo"
            )
        )
        return ToolResult.Success(
            content = "已添加待办事项（ID=$id）：$date $description。DailyPlanner 生成作息时会纳入考虑。",
            metadata = mapOf("todoId" to id, "date" to date, "description" to description)
        )
    }

    /**
     * 查询待办事项列表
     */
    private suspend fun listTodos(params: String): ToolResult {
        val today = LocalDate.now(zoneId).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weekLater = LocalDate.now(zoneId).plusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
        val todos = ServiceLocator.futureEventDao.getRange(agentId, today, weekLater)
            .filter { !it.consumed }
            .sortedBy { it.date }

        if (todos.isEmpty()) {
            return ToolResult.Success(content = "当前没有待办事项")
        }

        val content = buildString {
            appendLine("待办事项列表（共 ${todos.size} 项）：")
            todos.forEach { todo ->
                appendLine("- [ID=${todo.id}] ${todo.date}：${todo.description}")
            }
        }
        return ToolResult.Success(content = content)
    }

    /**
     * 标记待办事项完成
     */
    private suspend fun completeTodo(params: String): ToolResult {
        val todoId = parseLongField(params, "todoId")
            ?: return ToolResult.Error("complete 操作缺少 todoId 参数")

        val agentId = ServiceLocator.activeAgentManager.getRequiredActiveAgentId()
        ServiceLocator.futureEventDao.markConsumed(agentId, todoId)
        return ToolResult.Success(content = "待办事项（ID=$todoId）已标记为完成")
    }

    /**
     * 从 JSON 参数中提取字符串字段
     */
    private fun parseField(params: String, field: String): String? {
        return try {
            val obj = json.parseToJsonElement(params) as? JsonObject ?: return null
            (obj[field] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从 JSON 参数中提取长整数字段
     */
    private fun parseLongField(params: String, field: String): Long? {
        return try {
            val obj = json.parseToJsonElement(params) as? JsonObject ?: return null
            (obj[field] as? JsonPrimitive)?.content?.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
