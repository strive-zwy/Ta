package com.agent.ta.domain.config

import com.agent.ta.data.local.dao.ConfigSessionDao
import com.agent.ta.data.local.entity.ConfigSessionEntity
import com.agent.ta.data.model.AgentConfig
import com.agent.ta.data.remote.dto.ConfigUpdate
import kotlinx.serialization.json.Json

class ConfigSessionManager(
    private val dao: ConfigSessionDao
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun start(agentId: Long, currentConfig: AgentConfig): ConfigSession {
        dao.getByAgentId(agentId)?.let { return it.toModel() }
        val now = System.currentTimeMillis()
        val entity = ConfigSessionEntity(
            agentId = agentId,
            mode = ConfigSessionMode.UNSELECTED.name,
            stage = ConfigSessionStage.SELECTING_MODE.name,
            draftConfigJson = json.encodeToString(AgentConfig.serializer(), currentConfig),
            createdAt = now,
            updatedAt = now
        )
        dao.upsert(entity)
        return entity.toModel()
    }

    suspend fun get(agentId: Long): ConfigSession? = dao.getByAgentId(agentId)?.toModel()

    suspend fun getDraft(agentId: Long): AgentConfig? = get(agentId)?.draftConfig

    suspend fun selectMode(agentId: Long, mode: ConfigSessionMode): ConfigSession? {
        val current = dao.getByAgentId(agentId) ?: return null
        val stage = when (mode) {
            ConfigSessionMode.CUSTOM_CONVERSATION -> ConfigSessionStage.COLLECTING_CUSTOM
            ConfigSessionMode.CELEBRITY_REFERENCE,
            ConfigSessionMode.FICTIONAL_CHARACTER_REFERENCE -> ConfigSessionStage.COLLECTING_REFERENCE
            ConfigSessionMode.UNSELECTED -> ConfigSessionStage.SELECTING_MODE
        }
        val updated = current.copy(mode = mode.name, stage = stage.name, updatedAt = System.currentTimeMillis())
        dao.upsert(updated)
        return updated.toModel()
    }

    suspend fun save(entity: ConfigSessionEntity) = dao.upsert(entity.copy(updatedAt = System.currentTimeMillis()))

    suspend fun updateDraft(agentId: Long, config: AgentConfig, stage: ConfigSessionStage? = null): ConfigSession? {
        val current = dao.getByAgentId(agentId) ?: return null
        val updated = current.copy(
            draftConfigJson = json.encodeToString(AgentConfig.serializer(), config),
            stage = stage?.name ?: current.stage,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(updated)
        return updated.toModel()
    }

    suspend fun applyUpdate(agentId: Long, update: ConfigUpdate): ConfigSession? {
        val session = get(agentId) ?: return null
        val config = session.draftConfig
        val agent = config.agent
        val persona = agent.persona
        return updateDraft(
            agentId,
            config.copy(
                agent = agent.copy(
                    name = update.name ?: agent.name,
                    gender = update.gender ?: agent.gender,
                    age = update.age ?: agent.age,
                    persona = persona.copy(
                        background = update.background ?: persona.background,
                        personality = update.personality ?: persona.personality,
                        speakingStyle = update.speakingStyle ?: persona.speakingStyle,
                        selfNickname = update.selfNickname ?: persona.selfNickname,
                        nicknameForUser = update.nicknameForUser ?: persona.nicknameForUser,
                        relationshipToUser = update.relationshipToUser ?: persona.relationshipToUser,
                        catchphrases = update.catchphrases ?: persona.catchphrases,
                        interests = update.interests ?: persona.interests,
                        taboos = update.taboos ?: persona.taboos
                    )
                )
            )
        )
    }

    suspend fun updateReference(
        agentId: Long,
        referenceName: String,
        referenceWork: String,
        researchJson: String,
        config: AgentConfig
    ): ConfigSession? {
        val current = dao.getByAgentId(agentId) ?: return null
        val updated = current.copy(
            stage = ConfigSessionStage.REVIEWING.name,
            referenceName = referenceName,
            referenceWork = referenceWork,
            researchJson = researchJson,
            draftConfigJson = json.encodeToString(AgentConfig.serializer(), config),
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(updated)
        return updated.toModel()
    }

    suspend fun setStage(agentId: Long, stage: ConfigSessionStage): ConfigSession? {
        val current = dao.getByAgentId(agentId) ?: return null
        val updated = current.copy(stage = stage.name, updatedAt = System.currentTimeMillis())
        dao.upsert(updated)
        return updated.toModel()
    }

    suspend fun complete(agentId: Long) = dao.deleteByAgentId(agentId)

    private fun ConfigSessionEntity.toModel(): ConfigSession = ConfigSession(
        agentId = agentId,
        mode = enumValueOf(mode),
        stage = enumValueOf(stage),
        draftConfig = json.decodeFromString(AgentConfig.serializer(), draftConfigJson),
        researchJson = researchJson,
        referenceName = referenceName,
        referenceWork = referenceWork,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
