package com.agent.ta.domain.config

import com.agent.ta.data.model.AgentConfig

enum class ConfigSessionMode {
    UNSELECTED,
    CUSTOM_CONVERSATION,
    CELEBRITY_REFERENCE,
    FICTIONAL_CHARACTER_REFERENCE
}

enum class ConfigSessionStage {
    SELECTING_MODE,
    COLLECTING_CUSTOM,
    COLLECTING_REFERENCE,
    RESEARCHING,
    REVIEWING
}

data class ConfigSession(
    val agentId: Long,
    val mode: ConfigSessionMode,
    val stage: ConfigSessionStage,
    val draftConfig: AgentConfig,
    val researchJson: String,
    val referenceName: String,
    val referenceWork: String,
    val createdAt: Long,
    val updatedAt: Long
)
