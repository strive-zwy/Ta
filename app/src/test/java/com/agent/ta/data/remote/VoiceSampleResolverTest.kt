package com.agent.ta.data.remote

import com.agent.ta.data.model.VoiceConfig
import com.agent.ta.data.model.VoiceEmotionConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceSampleResolverTest {

    @Test
    fun `uses valid emotion sample`() {
        val neutral = tempSample("neutral")
        val happy = tempSample("happy")
        val config = config(neutral.absolutePath, happy.absolutePath)

        assertEquals(happy.absolutePath, VoiceSampleResolver.resolve(config, null, "happy"))
    }

    @Test
    fun `falls back to neutral when emotion sample is missing`() {
        val neutral = tempSample("neutral")
        val config = config(neutral.absolutePath, "missing-happy.wav")

        assertEquals(neutral.absolutePath, VoiceSampleResolver.resolve(config, null, "happy"))
    }

    @Test
    fun `returns null when configured samples do not exist`() {
        val config = config("missing-neutral.wav", "missing-happy.wav")

        assertNull(VoiceSampleResolver.resolve(config, null, "happy"))
    }

    @Test
    fun `uses valid legacy fallback when config has no valid sample`() {
        val fallback = tempSample("fallback")
        val config = config("", "")

        assertEquals(fallback.absolutePath, VoiceSampleResolver.resolve(config, fallback.absolutePath, "calm"))
    }

    private fun config(neutral: String, happy: String): VoiceConfig {
        return VoiceConfig(
            sampleFile = "",
            emotions = mapOf(
                VoiceEmotionConfig.NEUTRAL to VoiceEmotionConfig(sampleFile = neutral),
                VoiceEmotionConfig.HAPPY to VoiceEmotionConfig(sampleFile = happy),
                VoiceEmotionConfig.CALM to VoiceEmotionConfig()
            )
        )
    }

    private fun tempSample(name: String): File {
        return File.createTempFile(name, ".wav").apply {
            writeBytes("RIFF0000WAVEfmt ".toByteArray())
            deleteOnExit()
        }
    }
}
