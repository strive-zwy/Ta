package com.agent.ta.data.model

import kotlinx.serialization.Serializable

/**
 * 模型配置项（LLM 或 TTS 通用）
 *
 * 用于 [com.agent.ta.data.prefs.UserPreferences] 中存储多模型列表，
 * 每个 entry 对应一个可切换的模型配置。
 *
 * @param id 唯一标识（UUID 或时间戳生成的字符串）
 * @param name 用户可见的模型名称（如 "DeepSeek"、"Grok"、"MiMo"）
 * @param baseUrl API Base URL
 * @param apiKey API Key（加密存储）
 * @param model 模型标识（如 "deepseek-chat"、"grok-4.5"）
 */
@Serializable
data class ModelEntry(
    val id: String,
    val name: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = ""
)
