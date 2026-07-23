package com.agent.ta.data.remote.api

import com.agent.ta.data.remote.dto.ChatCompletionRequest
import com.agent.ta.data.remote.dto.ChatCompletionResponse
import com.agent.ta.data.remote.dto.VoiceCloneRequest
import com.agent.ta.data.remote.dto.TtsResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * OpenAI 兼容 LLM 接口
 */
interface LlmApi {
    @POST("chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") auth: String,
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
}

/**
 * MiMo TTS voiceclone 接口
 */
interface TtsApi {
    @POST("chat/completions")
    suspend fun voiceClone(
        @Header("Authorization") auth: String,
        @Body request: VoiceCloneRequest
    ): TtsResponse
}
