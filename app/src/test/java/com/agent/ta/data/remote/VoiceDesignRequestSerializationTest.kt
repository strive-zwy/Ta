package com.agent.ta.data.remote

import com.agent.ta.data.remote.dto.TtsMessage
import com.agent.ta.data.remote.dto.VoiceCloneAudioInput
import com.agent.ta.data.remote.dto.VoiceCloneRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证「音色设计」请求体必须省略 audio.voice 字段。
 *
 * 根因：mimo-v2.5-tts-voicedesign 官方文档明确【不支持 audio.voice 字段】。
 * 之前 buildVoiceDesignRequest 传了 voice=""（空字符串），且 Json 配置 encodeDefaults=true，
 * 导致请求体带上 "voice":""，API 拒绝或忽略音色描述 → 回退默认音色。
 * 修复后：voice 传 null，且 Json explicitNulls=false，序列化时彻底剔除该字段。
 */
class VoiceDesignRequestSerializationTest {

    // 与 TtsClient 内部一致的序列化配置
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `voicedesign request omits voice field`() {
        val request = VoiceCloneRequest(
            model = "mimo-v2.5-tts-voicedesign",
            messages = listOf(
                TtsMessage(role = "user", content = "温柔少女音，声线偏柔，尾音略上扬"),
                TtsMessage(role = "assistant", content = "你好呀")
            ),
            audio = VoiceCloneAudioInput(format = "wav", voice = null)
        )

        val jsonStr = json.encodeToString(VoiceCloneRequest.serializer(), request)

        assertFalse("voicedesign 请求体不得包含 voice 字段，实际：$jsonStr", jsonStr.contains("\"voice\""))
        assertTrue(jsonStr.contains("\"format\":\"wav\""))
        assertTrue(jsonStr.contains("\"role\":\"user\""))
        assertTrue(jsonStr.contains("\"role\":\"assistant\""))
    }

    @Test
    fun `voiceclone request keeps voice field`() {
        val request = VoiceCloneRequest(
            model = "mimo-v2.5-tts-voiceclone",
            messages = listOf(
                TtsMessage(role = "user", content = ""),
                TtsMessage(role = "assistant", content = "你好呀")
            ),
            audio = VoiceCloneAudioInput(format = "wav", voice = "data:audio/wav;base64,AAAA")
        )

        val jsonStr = json.encodeToString(VoiceCloneRequest.serializer(), request)

        assertTrue("voiceclone 请求体必须包含 voice 字段，实际：$jsonStr", jsonStr.contains("\"voice\":\"data:audio/wav;base64,AAAA\""))
    }

    @Test
    fun `preset request keeps preset voice field`() {
        val request = VoiceCloneRequest(
            model = "mimo-v2.5-tts",
            messages = listOf(
                TtsMessage(role = "user", content = ""),
                TtsMessage(role = "assistant", content = "你好呀")
            ),
            audio = VoiceCloneAudioInput(format = "wav", voice = "mimo_default")
        )

        val jsonStr = json.encodeToString(VoiceCloneRequest.serializer(), request)

        assertTrue("preset 请求体必须包含 voice=mimo_default，实际：$jsonStr", jsonStr.contains("\"voice\":\"mimo_default\""))
    }
}