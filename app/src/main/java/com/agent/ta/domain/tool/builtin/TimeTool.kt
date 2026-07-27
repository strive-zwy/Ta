package com.agent.ta.domain.tool.builtin

import com.agent.ta.domain.tool.AgentTool
import com.agent.ta.domain.tool.ToolContext
import com.agent.ta.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 当前时间工具（内置）
 *
 * 返回当前北京时间，含日期、时间、星期
 *
 * LLM 调用时机：
 * - 用户问"现在几点""今天星期几"
 * - Agent 需要基于时间组织回复（如"晚上好""快睡觉了"）
 *
 * 注意：PromptBuilder 已注入当前时间到 system prompt，
 *      此工具主要供 LLM 在对话中途需要精确时间时使用
 */
class TimeTool : AgentTool {
    override val name = "get_current_time"
    override val description = "获取当前时间（北京时间）。包含日期、时间、星期。当用户问现在几点、今天星期几时使用。"

    override val parameters: JsonElement = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(emptyMap())
    ))

    override suspend fun execute(params: String, context: ToolContext): ToolResult {
        val now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
        val dateStr = now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))
        val timeStr = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        val weekStr = when (now.dayOfWeek.value) {
            1 -> "星期一"
            2 -> "星期二"
            3 -> "星期三"
            4 -> "星期四"
            5 -> "星期五"
            6 -> "星期六"
            7 -> "星期日"
            else -> ""
        }

        val content = "当前时间：$dateStr $weekStr $timeStr（北京时间）"

        return ToolResult.Success(
            content = content,
            metadata = mapOf(
                "datetime" to now.toString(),
                "timestamp" to now.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
            )
        )
    }
}
