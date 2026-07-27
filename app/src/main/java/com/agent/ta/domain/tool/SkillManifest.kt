package com.agent.ta.domain.tool

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Skill 包元信息（v3 通用工具系统）
 *
 * 对应 .skill.zip 中的 skill.json
 * 描述一个 Skill 包含的工具、prompt 提示、配置项
 *
 * Skill 包格式：
 * ```
 * my-skill.skill.zip
 * ├── skill.json    # 此 Manifest
 * └── README.md     # 用户说明（可选）
 * ```
 *
 * skill.json 结构：
 * ```json
 * {
 *   "id": "web-search",
 *   "name": "联网搜索",
 *   "version": "1.0.0",
 *   "description": "让 Agent 能搜索互联网获取最新信息",
 *   "promptHint": "你具备联网搜索能力...",
 *   "tools": [{"name": "web_search", "executor": "builtin:web_search"}],
 *   "settings": {"maxResults": 5}
 * }
 * ```
 *
 * executor 类型：
 * - builtin:xxx：App 内置 Kotlin 实现（如 builtin:web_search）
 * - http:URL：HTTP 调用外部服务
 * - intent:action：Android Intent 调起系统应用
 */
@Serializable
data class SkillManifest(
    /** Skill 唯一 ID */
    val id: String,
    /** 显示名称 */
    val name: String,
    /** 版本号 */
    val version: String = "1.0.0",
    /** 描述（给用户看） */
    val description: String = "",
    /** 作者 */
    val author: String = "",
    /** 图标标识（builtin:xxx 或 URL） */
    val icon: String = "",
    /**
     * Prompt 提示（注入到 system prompt）
     *
     * 告诉 LLM 它具备这个能力，何时使用
     */
    @SerialName("prompt_hint") val promptHint: String = "",
    /**
     * 工具定义列表
     *
     * 内置工具用 executor: "builtin:xxx" 引用 App 内的实现
     * 自定义工具用 executor: "http:URL" 或 "intent:action"
     */
    val tools: List<SkillToolDef> = emptyList(),
    /**
     * Skill 配置项（默认值）
     *
     * 用户可在 App 端覆盖这些配置（如 maxResults、apiKey 等）
     */
    val settings: Map<String, JsonElement> = emptyMap()
)

/**
 * Skill 中的工具定义
 */
@Serializable
data class SkillToolDef(
    val name: String,
    val description: String,
    val parameters: JsonElement,
    /** 执行器标识：builtin:xxx / http:URL / intent:action */
    val executor: String
)
