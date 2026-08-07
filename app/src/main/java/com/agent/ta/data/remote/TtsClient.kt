package com.agent.ta.data.remote

import android.util.Base64
import android.util.Log
import com.agent.ta.data.model.VoiceConfig
import com.agent.ta.data.prefs.UserPreferences
import com.agent.ta.data.remote.dto.TtsMessage
import com.agent.ta.data.remote.dto.TtsResponse
import com.agent.ta.data.remote.dto.VoiceCloneAudioInput
import com.agent.ta.data.remote.dto.VoiceCloneRequest
import com.agent.ta.di.ServiceLocator
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * TTS 客户端（MiMo V2.5 TTS 系列）
 *
 * 根据当前 Agent 的语音配置自动选择三种模型之一：
 *
 * 1. **mimo-v2.5-tts-voiceclone**（音色复刻）：
 *    - 触发条件：voiceConfig.emotions[*].sampleFile 非空 或 voiceConfig.sampleFile 非空
 *    - 调用方式：audio.voice = base64 编码的样本音频（DataURL 格式）
 *    - 适用场景：用户上传了参考音频样本
 *
 * 2. **mimo-v2.5-tts-voicedesign**（音色设计）：
 *    - 触发条件：无样本 + voiceConfig.voiceDescription 非空
 *    - 调用方式：user message = 音色描述文本
 *    - 适用场景：用户通过对话描述想要的音色（如"温柔少女音"）
 *
 * 3. **mimo-v2.5-tts**（预置音色）：
 *    - 触发条件：无样本 + 无音色描述
 *    - 调用方式：audio.voice = "mimo_default" 或其他预置音色 ID
 *    - 适用场景：默认兜底，使用预置精品音色
 *
 * 所有模型都支持导演模式（user message 中传语速/情绪等指令）。
 */
class TtsClient {

    private val prefs = ServiceLocator.userPreferences

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // 复用 OkHttpClient 实例，避免每次调用都创建新的连接池和线程池
    private val okHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 合成语音
     *
     * @param text 要合成的文本
     * @param directorPrompt 导演模式指令（语速/情绪/风格等）
     * @param voiceSamplePath 默认样本路径（v1 兼容，优先级低于 config.emotions[emotion]）
     * @param config VoiceConfig（用于读取 emotions[emotion] / voiceDescription）
     * @param emotionHint 情绪标签（neutral/happy/calm），用于选择对应情绪的样本和参数
     * @return 音频文件字节数组，失败返回 null
     */
    suspend fun synthesize(
        text: String,
        directorPrompt: String,
        voiceSamplePath: String? = null,
        config: VoiceConfig? = null,
        emotionHint: String? = null
    ): TtsAudioResult? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            // 1. 解析样本路径（优先 config.emotions[emotion]，其次 v1 sampleFile，最后调用方传入）
            val resolvedSample = resolveSamplePath(config, voiceSamplePath, emotionHint)
            // 2. 根据样本和音色描述选择模型
            val mode = selectMode(resolvedSample, config)
            Log.d(TAG, "TTS 模式：$mode，samplePath=$resolvedSample，voiceDescription=${config?.voiceDescription?.take(30)}")

            // 3. 注入对应情绪的 voice_params 到 directorPrompt
            val finalDirectorPrompt = config?.let {
                TtsPromptPolicy.build(directorPrompt, it, emotionHint)
            } ?: TtsPromptPolicy.NATURAL_CHAT_BASELINE

            // 4. 根据模式构造请求
            val request = when (mode) {
                TtsMode.VOICECLONE -> buildVoiceCloneRequest(text, finalDirectorPrompt, resolvedSample!!, config)
                TtsMode.VOICEDESIGN -> buildVoiceDesignRequest(text, finalDirectorPrompt, config!!)
                TtsMode.PRESET -> buildPresetRequest(text, finalDirectorPrompt)
            }
            val requestJson = json.encodeToString(VoiceCloneRequest.serializer(), request)

            // 5. 发送请求（复用 OkHttpClient 实例）
            val body = requestJson.toRequestBody("application/json; charset=utf-8".toMediaType())
            val url = prefs.ttsBaseUrl.trimEnd('/') + "/chat/completions"
            val req = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${prefs.ttsApiKey}")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                val rawBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    Log.e(TAG, "TTS HTTP ${resp.code}（$mode）：${rawBody.take(500)}")
                    return@withContext null
                }
                val parsed = try {
                    json.decodeFromString(TtsResponse.serializer(), rawBody)
                } catch (e: Exception) {
                    Log.e(TAG, "TTS 响应解析失败，bodyLength=${rawBody.length}", e)
                    null
                }
                val audio = parsed?.choices?.firstOrNull()?.message?.audio
                val bytes = audio?.data?.takeIf { it.isNotBlank() }?.let { Base64.decode(it, Base64.DEFAULT) }
                    ?: audio?.downloadUrl?.takeIf { it.isNotBlank() }?.let { downloadAudio(it) }
                val result = bytes?.let { value ->
                    TtsAudioFormat.resolve(audio?.format, value)?.let { format ->
                        TtsAudioResult(value, format)
                    }
                }
                if (result != null) {
                    Log.d(TAG, "TTS 合成成功（$mode）：${result.bytes.size} bytes，format=${result.format}")
                } else {
                    Log.w(TAG, "TTS 响应无音频数据（$mode），choices=${parsed?.choices?.size ?: 0}，bodyLength=${rawBody.length}")
                }
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS synthesize 异常", e)
            null
        }
    }

    /**
     * 根据样本和配置选择 TTS 模式
     */
    private fun selectMode(resolvedSample: String?, config: VoiceConfig?): TtsMode {
        return when {
            // 有样本 → 音色复刻
            !resolvedSample.isNullOrBlank() -> TtsMode.VOICECLONE
            // 无样本 + 有音色描述 → 音色设计
            config != null && config.voiceDescription.isNotBlank() -> TtsMode.VOICEDESIGN
            // 兜底 → 预置音色
            else -> TtsMode.PRESET
        }
    }

    /**
     * 构造音色复刻请求（mimo-v2.5-tts-voiceclone）
     */
    private fun buildVoiceCloneRequest(
        text: String,
        directorPrompt: String,
        samplePath: String,
        config: VoiceConfig?
    ): VoiceCloneRequest {
        val voiceBase64 = loadVoiceSample(samplePath)
            ?: throw IllegalStateException("样本音频加载失败：$samplePath")
        val sampleFormat = resolveSampleFormat(samplePath)
        val voiceDataURL = toDataURL(sampleFormat, voiceBase64)
        Log.d(TAG, "voiceclone：sample=$samplePath, format=$sampleFormat, ${voiceBase64.length} chars base64")
        return VoiceCloneRequest(
            model = UserPreferences.TTS_MODEL_VOICECLONE,
            messages = listOf(
                TtsMessage(role = "user", content = directorPrompt),
                TtsMessage(role = "assistant", content = text)
            ),
            audio = VoiceCloneAudioInput(format = "wav", voice = voiceDataURL)
        )
    }

    /**
     * 构造音色设计请求（mimo-v2.5-tts-voicedesign）
     *
     * user message = 音色描述文本（如"年轻女性，温柔甜美，声线偏柔，尾音略上扬"）
     * assistant message = 要合成的文本
     */
    private fun buildVoiceDesignRequest(
        text: String,
        directorPrompt: String,
        config: VoiceConfig
    ): VoiceCloneRequest {
        // 音色描述作为 user message 的核心内容，directorPrompt 追加在后面作为风格指导
        val userContent = buildString {
            append(config.voiceDescription)
            if (directorPrompt.isNotBlank()) {
                append("\n\n")
                append(directorPrompt)
            }
        }
        Log.d(TAG, "voicedesign：voiceDescription=${config.voiceDescription.take(60)}")
        return VoiceCloneRequest(
            model = UserPreferences.TTS_MODEL_VOICEDESIGN,
            messages = listOf(
                TtsMessage(role = "user", content = userContent),
                TtsMessage(role = "assistant", content = text)
            ),
            audio = VoiceCloneAudioInput(format = "wav", voice = "")
        )
    }

    /**
     * 构造预置音色请求（mimo-v2.5-tts）
     *
     * audio.voice = 预置音色 ID（如 mimo_default / 冰糖 / Chloe 等）
     * user message = 导演指令（可选）
     */
    private fun buildPresetRequest(text: String, directorPrompt: String): VoiceCloneRequest {
        Log.d(TAG, "preset：使用 mimo_default 预置音色")
        return VoiceCloneRequest(
            model = UserPreferences.TTS_MODEL_TTS,
            messages = listOf(
                TtsMessage(role = "user", content = directorPrompt),
                TtsMessage(role = "assistant", content = text)
            ),
            audio = VoiceCloneAudioInput(format = "wav", voice = "mimo_default")
        )
    }

    /**
     * 解析样本路径：
     * 1. 优先用 config.emotions[emotionHint] 的样本（fallback 到 neutral）
     * 2. 回退到调用方传入的 voiceSamplePath
     */
    private fun resolveSamplePath(
        config: VoiceConfig?,
        fallbackPath: String?,
        emotionHint: String?
    ): String? {
        return VoiceSampleResolver.resolve(config, fallbackPath, emotionHint)
    }

    /**
     * 加载样本音频为 base64
     */
    private fun loadVoiceSample(path: String?): String? {
        val actualPath = path?.takeIf { it.isNotBlank() } ?: return null
        val file = File(actualPath)
        if (!file.isFile) {
            Log.w(TAG, "音频样本文件不存在：$actualPath")
            return null
        }
        return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    private fun resolveSampleFormat(path: String?): String {
        val ext = path?.substringAfterLast('.', "")?.lowercase()
        return when (ext) {
            "mp3" -> "mp3"
            else -> "wav"
        }
    }

    private fun toDataURL(format: String, base64Content: String): String {
        return "data:audio/$format;base64,$base64Content"
    }

    private suspend fun downloadAudio(url: String): ByteArray? {
        return try {
            val request = okhttp3.Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                response.body?.bytes()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 诊断合成：用当前配置发起一次真实 TTS 调用，返回详细结果供"测试语音"按钮展示
     */
    suspend fun diagnose(
        text: String,
        directorPrompt: String,
        voiceSamplePath: String? = null
    ): TtsDiagnosisResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val baseUrl = prefs.ttsBaseUrl
        val apiKeyConfigured = prefs.ttsApiKey.isNotBlank()

        if (!apiKeyConfigured) {
            return@withContext TtsDiagnosisResult(
                success = false,
                message = "TTS API Key 未配置",
                baseUrl = baseUrl, apiKeyConfigured = false, model = "(未配置)",
                samplePath = voiceSamplePath, sampleExists = false,
                sampleSizeBytes = 0, sampleFormat = "wav", base64Length = 0,
                error = "no_api_key"
            )
        }
        if (baseUrl.isBlank()) {
            return@withContext TtsDiagnosisResult(
                success = false,
                message = "TTS BaseUrl 未配置",
                baseUrl = baseUrl, apiKeyConfigured = true, model = "(未配置)",
                samplePath = voiceSamplePath, sampleExists = false,
                sampleSizeBytes = 0, sampleFormat = "wav", base64Length = 0,
                error = "no_base_url"
            )
        }

        // 用配置中的 VoiceConfig 进行诊断（与实际合成路径一致）
        val config = ServiceLocator.agentConfigProvider.get().voice
        val resolvedSample = resolveSamplePath(config, voiceSamplePath, null)
        val mode = selectMode(resolvedSample, config)
        val finalDirectorPrompt = TtsPromptPolicy.build(directorPrompt, config, null)

        val request = when (mode) {
            TtsMode.VOICECLONE -> {
                val voiceBase64 = loadVoiceSample(resolvedSample!!)
                if (voiceBase64 == null) {
                    return@withContext TtsDiagnosisResult(
                        success = false,
                        message = "样本音频加载失败：$resolvedSample",
                        baseUrl = baseUrl, apiKeyConfigured = true, model = UserPreferences.TTS_MODEL_VOICECLONE,
                        samplePath = resolvedSample, sampleExists = false,
                        sampleSizeBytes = 0, sampleFormat = "wav", base64Length = 0,
                        error = "sample_load_failed"
                    )
                }
                val sampleFormat = resolveSampleFormat(resolvedSample)
                val voiceDataURL = toDataURL(sampleFormat, voiceBase64)
                buildVoiceCloneRequest(text, finalDirectorPrompt, resolvedSample, config)
            }
            TtsMode.VOICEDESIGN -> buildVoiceDesignRequest(text, finalDirectorPrompt, config)
            TtsMode.PRESET -> buildPresetRequest(text, finalDirectorPrompt)
        }
        val model = when (mode) {
            TtsMode.VOICECLONE -> UserPreferences.TTS_MODEL_VOICECLONE
            TtsMode.VOICEDESIGN -> UserPreferences.TTS_MODEL_VOICEDESIGN
            TtsMode.PRESET -> UserPreferences.TTS_MODEL_TTS
        }
        val requestJson = json.encodeToString(VoiceCloneRequest.serializer(), request)
        Log.d(TAG, "诊断请求体（$mode）：${requestJson.take(500)}...")

        return@withContext try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = requestJson.toRequestBody(mediaType)
            val url = baseUrl.trimEnd('/') + "/chat/completions"
            val req = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${prefs.ttsApiKey}")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val code = resp.code
                val rawBody = resp.body?.string() ?: ""
                val preview = rawBody.take(2000)
                if (!resp.isSuccessful) {
                    TtsDiagnosisResult(
                        success = false,
                        message = "HTTP $code 错误（$mode）：$preview",
                        baseUrl = baseUrl, apiKeyConfigured = true, model = model,
                        samplePath = resolvedSample, sampleExists = resolvedSample != null,
                        sampleSizeBytes = 0, sampleFormat = "wav", base64Length = 0,
                        httpStatus = code, responsePreview = preview,
                        error = "http_error"
                    )
                } else {
                    val parsed = try {
                        json.decodeFromString(TtsResponse.serializer(), rawBody)
                    } catch (e: Exception) { null }
                    val audio = parsed?.choices?.firstOrNull()?.message?.audio
                    val audioData = audio?.data?.takeIf { it.isNotBlank() }
                    val resultBytes = audioData?.let { Base64.decode(it, Base64.DEFAULT) }
                    TtsDiagnosisResult(
                        success = resultBytes != null,
                        message = if (resultBytes != null) {
                            "合成成功（$mode），${resultBytes!!.size} bytes"
                        } else {
                            "响应成功但未解析到音频（$mode）。原始响应预览：$preview"
                        },
                        baseUrl = baseUrl, apiKeyConfigured = true, model = model,
                        samplePath = resolvedSample, sampleExists = resolvedSample != null,
                        sampleSizeBytes = 0, sampleFormat = "wav", base64Length = 0,
                        httpStatus = code, responsePreview = preview,
                        audioDataFound = audioData != null,
                        audioBytes = resultBytes?.size ?: 0,
                        error = if (resultBytes != null) null else "no_audio_in_response"
                    )
                }
            }
        } catch (e: Exception) {
            TtsDiagnosisResult(
                success = false,
                message = "请求异常（$mode）：${e.javaClass.simpleName}: ${e.message}",
                baseUrl = baseUrl, apiKeyConfigured = true, model = model,
                samplePath = resolvedSample, sampleExists = resolvedSample != null,
                sampleSizeBytes = 0, sampleFormat = "wav", base64Length = 0,
                error = "exception:${e.javaClass.simpleName}"
            )
        }
    }

    /**
     * TTS 工作模式
     */
    private enum class TtsMode {
        VOICECLONE,   // 音色复刻（有样本）
        VOICEDESIGN,  // 音色设计（无样本、有描述）
        PRESET        // 预置音色（兜底）
    }

    companion object {
        private const val TAG = "TtsClient"
    }
}

/**
 * TTS 诊断结果（用于「测试语音」按钮展示）
 */
data class TtsDiagnosisResult(
    val success: Boolean,
    val message: String,
    val baseUrl: String,
    val apiKeyConfigured: Boolean,
    val model: String,
    val samplePath: String?,
    val sampleExists: Boolean,
    val sampleSizeBytes: Long,
    val sampleFormat: String,
    val base64Length: Int,
    val httpStatus: Int? = null,
    val responsePreview: String? = null,
    val audioDataFound: Boolean = false,
    val audioBytes: Int = 0,
    val error: String? = null
)
