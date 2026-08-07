package com.agent.ta.data.remote

import com.agent.ta.data.model.VoiceConfig

object TtsPromptPolicy {
    const val NATURAL_CHAT_BASELINE = "像平时给熟人发语音一样自然地说，不朗诵，不刻意表演。"

    fun build(
        directorPrompt: String,
        config: VoiceConfig,
        emotionHint: String?
    ): String {
        val instructions = mutableListOf(NATURAL_CHAT_BASELINE)
        if (config.directorMode) {
            normalize(directorPrompt).takeIf { it.isNotBlank() }?.let(instructions::add)
            if (config.styleEnabled) {
                buildAcousticDirection(config, emotionHint)?.let(instructions::add)
            }
        }
        return instructions.joinToString("\n")
    }

    private fun normalize(prompt: String): String {
        return prompt
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .take(120)
    }

    private fun buildAcousticDirection(config: VoiceConfig, emotionHint: String?): String? {
        val params = config.voiceParamsFor(emotionHint)
        if (params.isEmpty()) return null
        val values = listOfNotNull(
            params["speed"]?.takeIf(String::isNotBlank)?.let { describeSpeed(it.toFloatOrNull()) },
            params["pitch"]?.takeIf(String::isNotBlank)?.let { describePitch(it.toFloatOrNull()) },
            params["volume"]?.takeIf(String::isNotBlank)?.let { "音量$it" },
            params["intonation"]?.takeIf(String::isNotBlank)?.let { "语调$it" }
        )
        return values.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "声音保持")
    }

    private fun describeSpeed(speed: Float?): String = when {
        speed == null -> "适中"
        speed <= 0.8f -> "偏慢"
        speed <= 0.95f -> "适中偏慢"
        speed <= 1.05f -> "适中"
        speed <= 1.25f -> "偏快"
        else -> "较快"
    }

    private fun describePitch(pitch: Float?): String = when {
        pitch == null -> "自然"
        pitch <= 0.8f -> "低沉"
        pitch <= 0.95f -> "偏低"
        pitch <= 1.05f -> "自然"
        pitch <= 1.3f -> "偏高"
        else -> "高亢"
    }
}
