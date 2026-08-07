package com.agent.ta.data.remote

import com.agent.ta.data.model.VoiceConfig
import com.agent.ta.data.model.VoiceEmotionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPromptPolicyTest {

    @Test
    fun `director mode off ignores per reply direction`() {
        val config = VoiceConfig(directorMode = false)

        val result = TtsPromptPolicy.build("语速很慢，句末拖音", config, "calm")

        assertEquals(TtsPromptPolicy.NATURAL_CHAT_BASELINE, result)
        assertFalse(result.contains("拖音"))
    }

    @Test
    fun `director mode on keeps concise per reply direction`() {
        val config = VoiceConfig(directorMode = true)

        val result = TtsPromptPolicy.build("  带着笑意，语速轻快。\n不要夸张表演  ", config, "happy")

        assertTrue(result.startsWith(TtsPromptPolicy.NATURAL_CHAT_BASELINE))
        assertTrue(result.contains("带着笑意，语速轻快。不要夸张表演"))
        assertFalse(result.contains("【自然度要求】"))
    }

    @Test
    fun `style disabled does not inject acoustic parameters`() {
        val config = VoiceConfig(
            directorMode = true,
            styleEnabled = false,
            emotions = mapOf(
                VoiceEmotionConfig.NEUTRAL to VoiceEmotionConfig(
                    voiceParams = mapOf("speed" to "0.75", "pitch" to "1.5")
                )
            )
        )

        val result = TtsPromptPolicy.build("自然地说", config, "neutral")

        assertFalse(result.contains("偏慢"))
        assertFalse(result.contains("高亢"))
    }

    @Test
    fun `style enabled appends one compact acoustic direction`() {
        val config = VoiceConfig(
            directorMode = true,
            styleEnabled = true,
            emotions = mapOf(
                VoiceEmotionConfig.NEUTRAL to VoiceEmotionConfig(
                    voiceParams = mapOf(
                        "speed" to "0.9",
                        "pitch" to "1.0",
                        "volume" to "自然",
                        "intonation" to "柔和"
                    )
                )
            )
        )

        val result = TtsPromptPolicy.build("语气放松", config, "neutral")

        assertTrue(result.contains("声音保持适中偏慢、自然、音量自然、语调柔和"))
        assertEquals(1, result.lines().count { it.startsWith("声音保持") })
    }
}
