package com.agent.ta.data.prefs

import com.agent.ta.data.model.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSecretPolicyTest {
    @Test
    fun `strips api keys from persisted models`() {
        val models = listOf(
            ModelEntry("a", "A", "https://a.example", "key-a", "model-a"),
            ModelEntry("b", "B", "https://b.example", "key-b", "model-b")
        )

        val persisted = ModelSecretPolicy.stripSecrets(models)

        assertTrue(persisted.all { it.apiKey.isEmpty() })
        assertEquals("https://a.example", persisted[0].baseUrl)
    }

    @Test
    fun `restores secrets by model id`() {
        val models = listOf(ModelEntry("a", "A", model = "model-a"))

        val restored = ModelSecretPolicy.restoreSecrets(models, mapOf("a" to "key-a"))

        assertEquals("key-a", restored.single().apiKey)
    }

    @Test
    fun `migration collects only non blank secrets`() {
        val models = listOf(
            ModelEntry("a", "A", apiKey = "key-a"),
            ModelEntry("b", "B"),
            ModelEntry("c", "C", apiKey = "key-c")
        )

        assertEquals(
            mapOf("a" to "key-a", "c" to "key-c"),
            ModelSecretPolicy.extractSecrets(models)
        )
    }
}
