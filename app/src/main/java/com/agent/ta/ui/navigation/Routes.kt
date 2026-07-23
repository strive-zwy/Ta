package com.agent.ta.ui.navigation

/**
 * 路由常量
 *
 * 新导航结构：进入 App → 直接 ChatScreen → 右上角设置图标 → ProfileScreen（含模型配置/作息/危险操作入口）
 */
object Routes {
    const val MAIN = "main"                 // 直接 ChatScreen 入口
    const val PROFILE = "profile"           // 设置页（从聊天页右上角进入）
    const val MODEL_CONFIG = "model_config" // 首次配置 LLM/TTS 模型
    const val MODEL_CONFIG_EDIT = "model_config_edit" // 从设置页进入的模型配置
    const val PERMISSION_GUIDE = "permission_guide"
    const val AGENT_DETAIL = "agent_detail"
    const val TODAY_SCHEDULE = "today_schedule" // 今日动态作息

    // Agent 配置页（从设置页进入）
    const val AGENT_CONFIG = "agent_config"           // Agent 配置入口
    const val AGENT_BASIC = "agent_basic"             // 基础信息
    const val AGENT_PERSONA = "agent_persona"         // 人格设定
    const val AGENT_AVATAR = "agent_avatar"           // 头像管理
    const val AGENT_VOICE = "agent_voice"             // 语音配置
    const val AGENT_BEHAVIOR = "agent_behavior"       // 行为配置
}
