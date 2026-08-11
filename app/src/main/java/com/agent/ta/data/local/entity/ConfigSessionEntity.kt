package com.agent.ta.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "config_sessions")
data class ConfigSessionEntity(
    @PrimaryKey val agentId: Long,
    val mode: String,
    val stage: String,
    val draftConfigJson: String,
    val researchJson: String = "",
    val referenceName: String = "",
    val referenceWork: String = "",
    val createdAt: Long,
    val updatedAt: Long
)
