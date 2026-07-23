package com.agent.ta.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * OpenAI 兼容 Chat Completion 请求
 */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.85,
    val maxTokens: Int? = null
)

@Serializable
data class ChatMessage(
    val role: String,        // system | user | assistant
    val content: String
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList()
)

@Serializable
data class Choice(
    val message: ChatMessage? = null,
    val finishReason: String? = null
)
