package com.agent.ta.ui.screens.chat

import com.agent.ta.data.model.AgentConfig
import com.agent.ta.data.model.EmojiBehavior
import com.agent.ta.di.ServiceLocator

/**
 * 内置 emoji 列表（用于表情面板）
 *
 * 与 PromptBuilder 注入给 LLM 的 emoji 列表保持一致。
 * 修改其中一处时请同步修改另一处。
 *
 * Admin v2: 支持读取 behavior.emoji.preferred_emojis，
 * 如果 Agent 配置了偏好 emoji，面板和 PromptBuilder 都会优先使用。
 */
object EmojiCatalog {

    /**
     * 按分类组织的 emoji 列表，用于底部表情面板展示
     */
    val categories: List<EmojiCategory> = listOf(
        EmojiCategory("常用", listOf(
            "😄", "😂", "🤣", "😏", "😎", "😊",
            "🤔", "😅", "😑", "🙄", "😮‍💨",
            "😮", "😲", "🤯",
            "🤨", "😕", "🤷",
            "😤", "😠", "😒",
            "😢", "🥺", "😔", "😞",
            "🥰", "😘", "🤗", "😍", "🥹",
            "😴", "🌙", "☕", "🍚", "🛏️", "👋", "👌", "👍", "💬"
        )),
        EmojiCategory("开心", listOf(
            "😄", "😂", "🤣", "😏", "😎", "😊", "🥹", "😍"
        )),
        EmojiCategory("无奈", listOf(
            "🤔", "😅", "😑", "🙄", "😮‍💨"
        )),
        EmojiCategory("惊讶", listOf(
            "😮", "😲", "🤯", "😯"
        )),
        EmojiCategory("疑惑", listOf(
            "🤨", "😕", "🤷"
        )),
        EmojiCategory("生气", listOf(
            "😤", "😠", "😒"
        )),
        EmojiCategory("委屈", listOf(
            "😢", "🥺", "😔", "😞"
        )),
        EmojiCategory("亲昵", listOf(
            "🥰", "😘", "🤗", "😍", "🥹"
        )),
        EmojiCategory("日常", listOf(
            "😴", "🌙", "☕", "🍚", "🛏️", "👋", "👌", "👍", "💬"
        ))
    )

    /** 默认展示的扁平 emoji 列表（"常用"分类） */
    val defaultEmojis: List<String> = categories.first().emojis

    /**
     * Admin v2: 获取当前 Agent 的 emoji 配置
     * - 若启用且配置了 preferred_emojis，返回偏好列表（去重保留顺序）
     * - 若未启用，返回空列表（调用方应隐藏表情按钮）
     * - 若启用但未配置偏好，返回内置默认列表
     */
    fun resolveActiveEmojis(): List<String> {
        val config: AgentConfig = try {
            ServiceLocator.agentConfigProvider.get()
        } catch (e: Exception) {
            return defaultEmojis
        }
        return resolveActiveEmojis(config)
    }

    /**
     * 同上，但允许传入 AgentConfig，便于 ChatScreen 用 collectAsState 后的 config 直接调用
     */
    fun resolveActiveEmojis(config: AgentConfig): List<String> {
        val behavior: EmojiBehavior = config.behavior.emoji
        if (!behavior.enabled) return emptyList()
        val preferred = behavior.preferredEmojis.filter { it.isNotBlank() }
        if (preferred.isNotEmpty()) {
            // 偏好列表去重保留顺序
            val seen = mutableSetOf<String>()
            return preferred.filter { seen.add(it) }
        }
        return defaultEmojis
    }

    /**
     * Admin v2: 当前 Agent 是否启用了 emoji 功能
     */
    fun isEmojiEnabled(config: AgentConfig): Boolean {
        return config.behavior.emoji.enabled
    }

    /**
     * Admin v2: 单条消息 emoji 数量上限
     */
    fun maxPerMessage(config: AgentConfig): Int {
        return config.behavior.emoji.maxPerMessage.coerceAtLeast(1)
    }
}

data class EmojiCategory(
    val name: String,
    val emojis: List<String>
)

