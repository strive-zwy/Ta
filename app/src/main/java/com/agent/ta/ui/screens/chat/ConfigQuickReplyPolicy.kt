package com.agent.ta.ui.screens.chat

data class ConfigQuickReplyOption(
    val id: String,
    val label: String,
    val message: String
)

enum class ConfigQuickReplyAction {
    CUSTOM,
    CELEBRITY,
    FICTIONAL
}

object ConfigQuickReplyPolicy {
    const val ENTRY_MESSAGE = "已进入配置模式。请选择创建方式，也可以直接输入你的想法。\n配置会先整理成草稿，等你确认后才会正式保存。\n完成配置时输入 /done。"

    private val entryOptions = listOf(
        ConfigQuickReplyOption("custom", "对话式沟通自定义", "对话式沟通自定义"),
        ConfigQuickReplyOption("celebrity", "偶像参考（偶像克隆）", "偶像参考（偶像克隆）"),
        ConfigQuickReplyOption("fictional", "动画或动漫人物参考", "动画或动漫人物参考")
    )

    private val reviewOptions = listOf(
        ConfigQuickReplyOption("apply", "确认应用", "确认应用"),
        ConfigQuickReplyOption("edit", "继续修改", "继续修改"),
        ConfigQuickReplyOption("sources", "查看资料来源", "查看资料来源"),
        ConfigQuickReplyOption("regenerate", "重新生成", "重新生成")
    )

    fun resolve(text: String?): List<ConfigQuickReplyOption> = when {
        text == ENTRY_MESSAGE -> entryOptions
        text?.startsWith("Agent 配置草稿\n") == true -> reviewOptions
        else -> emptyList()
    }

    fun matchAction(text: String): ConfigQuickReplyAction? {
        val value = text.trim()
        return when {
            value == "对话式沟通自定义" || value.contains("自己描述") || value.contains("自定义") -> ConfigQuickReplyAction.CUSTOM
            value == "动画或动漫人物参考" || value.contains("动漫") || value.contains("动画") || value.contains("游戏角色") || value.contains("虚构角色") -> ConfigQuickReplyAction.FICTIONAL
            value == "偶像参考（偶像克隆）" || value.contains("偶像") || value.contains("明星") || value.contains("真人") || value.contains("参考") -> ConfigQuickReplyAction.CELEBRITY
            else -> null
        }
    }
}
