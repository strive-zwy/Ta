package com.agent.ta.domain

import android.util.Log
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.data.model.AgentState
import com.agent.ta.data.model.AvatarConfig
import java.io.File
import kotlin.math.abs

/**
 * 头像解析器
 *
 * 用户只需上传多张头像，运行时由 Agent 侧自行挑选：
 * 1. 兼容旧配置：若仍有 trigger_keywords / emotion_mapping / bindState 绑定则优先匹配
 * 2. 否则在可用头像池中，按「状态 + 回复文本」稳定哈希挑选（同一语境稳定、不同语境可切换）
 * 3. 若配置未提供头像，或文件不存在，返回 null（UI 兜底显示文字头像）
 */
object AvatarResolver {

    private const val TAG = "AvatarResolver"

    /**
     * 解析当前状态的头像本地路径
     *
     * @param config 当前 Agent 配置
     * @param state 当前 Agent 状态
     * @param replyText Agent 最近一条回复文本（用于关键词命中或自由哈希）
     * @param emotionHint 当前推断的情绪标签（兼容旧 emotion_mapping）
     * @return 头像文件本地绝对路径；不存在则返回 null
     */
    fun resolveAvatarPath(
        config: AgentConfig,
        state: AgentState,
        replyText: String? = null,
        emotionHint: String? = null
    ): String? {
        val avatars = config.agent.avatars.filter { it.file.isNotBlank() }
        if (avatars.isEmpty()) return null

        val candidate = pickByTriggerKeyword(avatars, replyText)
            ?: pickByEmotion(avatars, emotionHint)
            ?: pickByState(avatars, state)
            ?: pickByState(avatars, state, requireMood = false)
            ?: pickFree(avatars, state, replyText)
            ?: return null

        return candidate.file.takeIf { it.isNotBlank() }
            ?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    path
                } else {
                    Log.w(TAG, "头像文件不存在：$path")
                    null
                }
            }
    }

    /**
     * 兼容旧配置：按触发关键词匹配
     */
    private fun pickByTriggerKeyword(avatars: List<AvatarConfig>, replyText: String?): AvatarConfig? {
        if (replyText.isNullOrBlank()) return null
        return avatars.firstOrNull { av ->
            av.triggerKeywords.any { kw ->
                kw.isNotBlank() && replyText.contains(kw, ignoreCase = true)
            }
        }
    }

    /**
     * 兼容旧配置：按情绪映射匹配
     */
    private fun pickByEmotion(avatars: List<AvatarConfig>, emotionHint: String?): AvatarConfig? {
        if (emotionHint.isNullOrBlank()) return null
        return avatars.firstOrNull { av ->
            av.emotionMapping.any { it.equals(emotionHint, ignoreCase = true) }
        }
    }

    /**
     * 兼容旧配置：按状态绑定匹配
     */
    private fun pickByState(
        avatars: List<AvatarConfig>,
        state: AgentState,
        requireMood: Boolean = true
    ): AvatarConfig? {
        return avatars.firstOrNull { av ->
            av.bindState == state.id && (!requireMood || !av.bindMood.isNullOrBlank())
        }
    }

    /**
     * 自由池挑选：优先无绑定头像；若全部有绑定则用全部。
     * 用状态 + 回复文本做稳定哈希，保证同一语境不乱跳、不同语境可切换。
     */
    private fun pickFree(
        avatars: List<AvatarConfig>,
        state: AgentState,
        replyText: String?
    ): AvatarConfig? {
        if (avatars.isEmpty()) return null
        val free = avatars.filter { isUnbound(it) }.ifEmpty { avatars }
        val seed = (replyText?.hashCode() ?: 0) xor state.id.hashCode() xor free.size
        val idx = abs(seed) % free.size
        return free[idx]
    }

    private fun isUnbound(av: AvatarConfig): Boolean {
        return av.bindState.isNullOrBlank()
            && av.bindMood.isNullOrBlank()
            && av.triggerKeywords.isEmpty()
            && av.emotionMapping.isEmpty()
    }

    /**
     * 根据状态推断情绪标签（兼容旧 emotion_mapping / 语音样本 emotion）
     */
    fun inferEmotion(state: AgentState): String = when (state) {
        AgentState.GAME -> "excited"
        AgentState.HAPPY -> "happy"
        AgentState.WORK -> "serious"
        AgentState.SLEEP, AgentState.BATH -> "soft"
        else -> "neutral"
    }
}
