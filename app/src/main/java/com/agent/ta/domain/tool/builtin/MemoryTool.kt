package com.agent.ta.domain.tool.builtin

import com.agent.ta.domain.tool.AgentTool
import com.agent.ta.domain.tool.ToolContext
import com.agent.ta.domain.tool.ToolResult
import com.agent.ta.di.ServiceLocator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray

/**
 * 记忆查询工具（内置）
 *
 * 让 LLM 可以主动检索历史记忆，回答用户关于过去对话/事件的问题
 *
 * 调用时机：
 * - 用户问"你记得我们之前聊过 xxx 吗" → LLM 调 search
 * - 用户问"我之前说过我喜欢什么" → LLM 调 search
 * - 用户提到过去的事件，LLM 不确定细节 → LLM 调 search
 *
 * 与 PromptBuilder 的区别：
 * - PromptBuilder 自动注入 top 20 记忆（最近/重要的）
 * - MemoryTool 让 LLM 主动搜索特定关键词，检索更早或更细节的记忆
 */
class MemoryTool : AgentTool {
    override val name = "query_memory"
    override val description = "查询你的历史记忆。当用户问起过去聊过的话题、做过的事、你或用户提过的细节时使用。支持按关键词搜索或按类型查询。"

    override val parameters: JsonElement = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "keyword" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("搜索关键词（如「电影」「生日」「工作」）。留空则返回最近的重要记忆")
            )),
            "type" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("记忆类型筛选：user_profile（用户画像）/ event（事件）/ preference（偏好）/ relationship（关系）。留空不筛选"),
                "enum" to JsonArray(listOf(
                    JsonPrimitive("user_profile"),
                    JsonPrimitive("event"),
                    JsonPrimitive("preference"),
                    JsonPrimitive("relationship")
                ))
            )),
            "limit" to JsonObject(mapOf(
                "type" to JsonPrimitive("number"),
                "description" to JsonPrimitive("返回数量上限，默认 10")
            ))
        )),
        "required" to JsonArray(emptyList())
    ))

    private val zoneId = java.time.ZoneId.of("Asia/Shanghai")
    private val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")

    override suspend fun execute(params: String, context: ToolContext): ToolResult {
        val keyword = parseField(params, "keyword")
        val type = parseField(params, "type")
        val limit = parseLongField(params, "limit")?.toInt() ?: 10

        val memories = when {
            // 关键词搜索优先
            !keyword.isNullOrBlank() -> {
                ServiceLocator.memoryDao.searchByKeyword(keyword, limit.coerceAtMost(20))
            }
            // 按类型查询
            !type.isNullOrBlank() -> {
                ServiceLocator.memoryDao.getByType(type).take(limit)
            }
            // 默认返回 top N
            else -> {
                ServiceLocator.memoryDao.getTopMemories(limit)
            }
        }

        if (memories.isEmpty()) {
            return ToolResult.Success(content = if (!keyword.isNullOrBlank()) {
                "没有找到与「$keyword」相关的记忆"
            } else {
                "当前没有任何记忆"
            })
        }

        val content = buildString {
            appendLine("找到 ${memories.size} 条记忆：")
            memories.forEach { memory ->
                val time = java.time.Instant.ofEpochMilli(memory.createdAt)
                    .atZone(zoneId)
                    .format(dateFormatter)
                val typeLabel = when (memory.type) {
                    "user_profile" -> "用户"
                    "event" -> "事件"
                    "preference" -> "偏好"
                    "relationship" -> "关系"
                    else -> memory.type
                }
                appendLine("- [$time][$typeLabel] ${memory.content}")
            }
        }
        return ToolResult.Success(content = content)
    }

    private fun parseField(params: String, field: String): String? {
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(params) as? JsonObject
                ?: return null
            (obj[field] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseLongField(params: String, field: String): Long? {
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(params) as? JsonObject
                ?: return null
            (obj[field] as? JsonPrimitive)?.content?.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
