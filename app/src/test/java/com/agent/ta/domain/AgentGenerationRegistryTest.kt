package com.agent.ta.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGenerationRegistryTest {
    @Test
    fun `new generation invalidates old context`() {
        val registry = AgentGenerationRegistry()
        val old = registry.capture(7)

        registry.advance()

        assertFalse(registry.isCurrent(old))
        assertTrue(registry.isCurrent(registry.capture(7)))
    }

    @Test
    fun `different agent is never current`() {
        val registry = AgentGenerationRegistry()
        val context = registry.capture(7)

        assertFalse(registry.isCurrent(context, 8))
    }
}
