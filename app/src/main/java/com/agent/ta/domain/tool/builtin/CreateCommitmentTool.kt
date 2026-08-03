package com.agent.ta.domain.tool.builtin

import com.agent.ta.data.local.entity.CommitmentEntity
import com.agent.ta.di.ServiceLocator
import com.agent.ta.domain.tool.AgentTool
import com.agent.ta.domain.tool.ToolContext
import com.agent.ta.domain.tool.ToolResult
import com.agent.ta.service.CommitmentScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 承诺创建工具（内置）
 *
 * 让 LLM 可以在对话中创建承诺/约定/提醒，写入 commitments 表并由 AlarmManager 调度触发。
 *
 * 三种类型：
 * - appointment：双方约定（如"下午3点一起看电影"，各自同时看）
 * - promise：Agent 承诺（如"明天我帮你查 XXX"）
 * - reminder：提醒用户（如"明天叫我起床"）
 *
 * 调用时机：
 * - 用户说"下午一起看电影吧" → LLM 答应后调用（appointment）
 * - LLM 说"明天我帮你查 XXX" → 主动调用（promise）
 * - 用户说"明天叫我起床" → 调用（reminder）
 */
class CreateCommitmentTool : AgentTool {
    override val name = "create_commitment"
    override val description = """
        创建一个承诺/约定/提醒。
        - 答应和用户一起做某事（各自同时看/听/玩）→ type="appointment"
        - 承诺自己做某事 → type="promise"
        - 提醒用户做某事 → type="reminder"
        调用时机：
        - 用户说"下午一起看电影吧" → 你答应后调用
        - 你说"明天我帮你查 XXX" → 主动调用
        - 用户说"明天叫我起床" → 调用 reminder
    """.trimIndent()

    override val parameters: kotlinx.serialization.json.JsonElement = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
            "type" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("承诺类型：appointment（双方约定）/ promise（Agent 承诺）/ reminder（提醒用户）"),
                "enum" to JsonArray(listOf(
                    JsonPrimitive("appointment"),
                    JsonPrimitive("promise"),
                    JsonPrimitive("reminder")
                ))
            )),
            "content" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("承诺内容描述（如「一起看《星际穿越》电影」）")
            )),
            "triggerAt" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("触发时间 ISO 8601 格式（如 2026-07-30T15:00:00），可选。无精确触发时间时省略")
            ))
        )),
        "required" to JsonArray(listOf(
            JsonPrimitive("type"),
            JsonPrimitive("content")
        ))
    ))

    private val json = Json { ignoreUnknownKeys = true }
    private val zoneId = ZoneId.of("Asia/Shanghai")

    override suspend fun execute(params: String, context: ToolContext): ToolResult {
        val type = parseField(params, "type")
            ?: return ToolResult.Error("缺少 type 参数")
        val content = parseField(params, "content")
            ?: return ToolResult.Error("缺少 content 参数")
        val triggerAtStr = parseField(params, "triggerAt")
        val triggerAt = triggerAtStr?.let { parseIso8601(it) }

        // 根据类型确定参与者
        val participants = when (type) {
            "appointment" -> "agent,user"
            "promise" -> "agent"
            "reminder" -> "user"
            else -> "agent"
        }

        val commitment = CommitmentEntity(
            type = type,
            content = content,
            participants = participants,
            triggerAt = triggerAt,
            deadline = null,
            status = "pending",
            source = "tool",
            relatedMessageId = null
        )
        val id = ServiceLocator.commitmentDao.insert(commitment)

        // 若有未来触发时间，注册 AlarmManager 精确闹钟
        if (triggerAt != null && triggerAt > System.currentTimeMillis()) {
            CommitmentScheduler(context.appContext).scheduleCommitmentTrigger(
                commitment.copy(id = id)
            )
        }

        val timeStr = triggerAt?.let { formatTime(it) } ?: "今日内"
        return ToolResult.Success(
            content = "承诺已记录：$content（$timeStr）",
            metadata = mapOf(
                "commitmentId" to id,
                "type" to type,
                "triggerAt" to (triggerAt ?: 0L)
            )
        )
    }

    /**
     * 解析 ISO 8601 时间字符串为毫秒时间戳
     * 支持：
     * - 带时区的 ISO 8601（如 2026-07-30T15:00:00+08:00）
     * - 不带时区的本地时间（按 Asia/Shanghai 解析，如 2026-07-30T15:00:00）
     * - 直接的 epoch 毫秒数字
     */
    private fun parseIso8601(input: String): Long? {
        return try {
            // 优先尝试带时区的 Instant 解析
            Instant.parse(input).toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                // 退回到本地时间解析（按 Asia/Shanghai 时区）
                LocalDateTime.parse(input, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            } catch (e2: DateTimeParseException) {
                // 最后尝试当作纯毫秒时间戳
                input.toLongOrNull()
            }
        }
    }

    /**
     * 将毫秒时间戳格式化为可读时间字符串（Asia/Shanghai 时区）
     */
    private fun formatTime(epochMillis: Long): String {
        val localDateTime = Instant.ofEpochMilli(epochMillis)
            .atZone(zoneId)
            .toLocalDateTime()
        return localDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
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
}
