package com.agent.ta.data.remote

import com.agent.ta.data.model.VoiceConfig
import com.agent.ta.data.model.VoiceEmotionConfig
import java.io.File

object VoiceSampleResolver {
    fun resolve(config: VoiceConfig?, fallbackPath: String?, emotionHint: String?): String? {
        val candidates = buildList {
            if (config != null) {
                val emotion = VoiceEmotionConfig.normalize(emotionHint)
                config.emotions[emotion]?.sampleFile?.let(::add)
                config.emotions[VoiceEmotionConfig.NEUTRAL]?.sampleFile?.let(::add)
                add(config.sampleFile)
            }
            fallbackPath?.let(::add)
        }
        return candidates
            .asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .firstOrNull { File(it).isFile }
    }
}
