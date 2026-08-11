package com.agent.ta.data.remote

import com.agent.ta.data.model.VoiceConfig

object TtsPromptPolicy {
    const val NATURAL_CHAT_BASELINE = "像平时给熟人发语音一样自然、平稳地说，不朗诵，不刻意表演，不添加笑声、哼声或与文字无关的声音。"

    fun build(
        directorPrompt: String,
        config: VoiceConfig,
        emotionHint: String?
    ): String {
        val instructions = mutableListOf(NATURAL_CHAT_BASELINE)
        if (config.directorMode && config.styleEnabled) {
            instructions.add("保持自然、平稳的语速和语调，只做轻微情绪变化，不改变整体说话节奏。")
        }
        return instructions.joinToString("\n")
    }
}
