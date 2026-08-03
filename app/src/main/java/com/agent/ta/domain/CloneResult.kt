package com.agent.ta.domain

import com.agent.ta.data.model.AgentIdentity

/**
 * 克隆生成结果 DTO（内部传输用）
 *
 * 封装 LLM 一次性生成的完整身份设定：
 * - identity：11 字段（7 核心 + 4 publicProfile），作为 Agent 身份内核
 * - persona 联动字段：5 个，用于同步更新 persona，避免 identity 与 persona 割裂
 *
 * 写入时由 [CelebrityCloner.applyToConfig] 覆盖到 AgentConfig，保留 voice/avatars/behavior
 */
data class CloneResult(
    val identity: AgentIdentity,
    /** 联动 persona.background（第三人称背景描述） */
    val personaBackground: String,
    /** 联动 persona.personality 标签列表 */
    val personaPersonality: List<String>,
    /** 联动 persona.interests 标签列表 */
    val personaInterests: List<String>,
    /** 联动 persona.speakingStyle（说话风格概述） */
    val personaSpeakingStyle: String,
    /** 联动 persona.directorRoleTemplate（TTS 导演角色模板） */
    val personaDirectorRoleTemplate: String,
    /** 启发人物性别（male/female），覆盖到 AgentInfo.gender */
    val gender: String = "",
    /** 启发人物年龄，覆盖到 AgentInfo.age（0 表示未推断） */
    val age: Int = 0
)
