package com.agent.ta.data.remote

import com.agent.ta.data.model.VoiceConfig
import com.agent.ta.data.model.VoiceEmotionConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 验证「音色描述但不传样本」时应走 VOICEDESIGN 而非 VOICECLONE
 *
 * 根因：VoiceConfig.sampleFile 默认值为 "voice/sample.wav"（非空），
 * 且语音配置页的 v1 兼容逻辑会把它反填进 emotions[neutral].sampleFile。
 * 若该路径在运行时被误判为存在，selectMode 会错误进入 VOICECLONE 而忽略描述。
 * 本测试确认：只要样本文件真实不存在，resolver 必须返回 null（→ VOICEDESIGN）。
 */
class VoiceSampleResolverTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    @Test
    fun `description only with phantom default path returns null`() {
        // 模拟：只填了描述、没上传样本的真实运行场景
        // sampleFile 被反填成默认的 "voice/sample.wav"，但磁盘上并不存在该文件
        val config = VoiceConfig(
            sampleFile = "voice/sample.wav",
            voiceDescription = "温柔少女音，声线偏柔，尾音略上扬",
            emotions = mapOf(
                VoiceEmotionConfig.NEUTRAL to VoiceEmotionConfig(sampleFile = "voice/sample.wav")
            )
        )

        val resolved = VoiceSampleResolver.resolve(config, fallbackPath = "voice/sample.wav", emotionHint = "neutral")

        assertNull("文件不存在时不能把默认占位路径当作样本", resolved)
    }

    @Test
    fun `real sample file exists returns its path`() {
        val sample = tmp.newFile("sample.wav").apply { writeBytes(ByteArray(4)) }

        val config = VoiceConfig(
            sampleFile = sample.absolutePath,
            voiceDescription = "温柔少女音",
            emotions = mapOf(
                VoiceEmotionConfig.NEUTRAL to VoiceEmotionConfig(sampleFile = sample.absolutePath)
            )
        )

        val resolved = VoiceSampleResolver.resolve(config, fallbackPath = null, emotionHint = "neutral")

        assertEquals(sample.absolutePath, resolved)
        assertEquals(true, File(resolved!!).isFile)
    }
}