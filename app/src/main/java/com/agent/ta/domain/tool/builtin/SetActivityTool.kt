package com.agent.ta.domain.tool.builtin

import com.agent.ta.data.model.AgentState
import com.agent.ta.domain.anchor.ActivityAnchorManager
import com.agent.ta.domain.tool.AgentTool
import com.agent.ta.domain.tool.ToolContext
import com.agent.ta.domain.tool.ToolResult
import com.agent.ta.di.ServiceLocator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 设置当前活动工具（内置）
 *
 * 让 LLM 可以显式声明"我现在在做什么"，写入 ActivityAnchorManager，
 * 作为应用侧权威状态锚点，解决前后回复活动状态矛盾的问题。
 *
 * 设计动机：
 * - 作息表只能给出粗粒度活动（如"健身"），但 LLM 可能微调（"提前洗完澡了"）
 * - 没有 this tool 时，LLM 只能在 replyText 里说，但下次回复可能就忘了
 * - 有了 this tool，活动状态被持久化，后续回复的 PromptBuilder 会注入同一锚点
 *
 * 调用时机：
 * - LLM 决定调整作息（scheduleAdjustment.shouldAdjust=true）后，同步更新锚点
 * - 用户问"在干嘛"，LLM 回复后显式确认当前活动
 * - 活动自然结束（如"洗完澡了"）时更新到下一个活动
 *
 * 不需要每次回复都调用——只在活动状态发生变化时调用。
 */
class SetActivityTool : AgentTool {
    override val name = "set_activity"
    override val description = "设置你当前正在做的活动。当你决定调整作息、活动状态发生变化、或想明确告诉系统你现在在做什么时调用。不需要每次回复都调用——只在活动状态变化时调用。"

    override val parameters: JsonElement = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "activity" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("当前活动内容，简短具体（如「洗澡」「写代码」「陪她聊天」）")
            )),
            "state" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("对应的宏观状态：normal（日常/空闲）/ busy（忙碌）/ idle（无聊/发呆）/ unavailable（不可打扰，如睡觉）"),
                "enum" to JsonArray(listOf(
                    JsonPrimitive("normal"),
                    JsonPrimitive("busy"),
                    JsonPrimitive("idle"),
                    JsonPrimitive("unavailable")
                ))
            )),
            "durationMinutes" to JsonObject(mapOf(
                "type" to JsonPrimitive("integer"),
                "description" to JsonPrimitive("预计持续时长（分钟），到期后自动回退到作息表派生。范围 5-480")
            ))
        )),
        "required" to JsonArray(listOf(JsonPrimitive("activity"), JsonPrimitive("state"), JsonPrimitive("durationMinutes")))
    ))

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(params: String, context: ToolContext): ToolResult {
        val activity = parseField(params, "activity")
            ?: return ToolResult.Error("缺少 activity 参数")
        val stateStr = parseField(params, "state")
            ?: return ToolResult.Error("缺少 state 参数")
        val state = AgentState.fromId(stateStr)
            ?: return ToolResult.Error("未知状态：$stateStr，支持 normal/busy/idle/unavailable")
        val durationMinutes = parseIntField(params, "durationMinutes")
            ?: return ToolResult.Error("缺少 durationMinutes 参数")

        if (durationMinutes < 5 || durationMinutes > 480) {
            return ToolResult.Error("durationMinutes 应在 5-480 范围内，当前：$durationMinutes")
        }

        val anchorManager: ActivityAnchorManager = ServiceLocator.activityAnchorManager
        val anchor = anchorManager.setActivityFromLlm(activity, state, durationMinutes)

        return ToolResult.Success(
            content = "已设置当前活动：${anchor.activity}（状态：${state.displayName}，持续 ${durationMinutes} 分钟）。后续回复会以此活动为准。",
            metadata = mapOf(
                "activity" to activity,
                "state" to state.id,
                "durationMinutes" to durationMinutes
            )
        )
    }

    private fun parseField(params: String, field: String): String? {
        return try {
            val obj = json.parseToJsonElement(params) as? JsonObject ?: return null
            (obj[field] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseIntField(params: String, field: String): Int? {
        return try {
            val obj = json.parseToJsonElement(params) as? JsonObject ?: return null
            (obj[field] as? JsonPrimitive)?.content?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
