package com.agent.ta.data.prefs

import com.agent.ta.data.model.ModelEntry

object ModelSecretPolicy {
    fun stripSecrets(models: List<ModelEntry>): List<ModelEntry> =
        models.map { it.copy(apiKey = "") }

    fun extractSecrets(models: List<ModelEntry>): Map<String, String> =
        models.asSequence()
            .filter { it.apiKey.isNotBlank() }
            .associate { it.id to it.apiKey }

    fun restoreSecrets(
        models: List<ModelEntry>,
        secrets: Map<String, String>
    ): List<ModelEntry> = models.map { model ->
        model.copy(apiKey = secrets[model.id].orEmpty())
    }
}
