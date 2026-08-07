package com.agent.ta.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmDiagnosisResultTest {
    @Test
    fun `success requires non blank reply`() {
        assertTrue(LlmDiagnosisResult.fromReply(" hello ", 120).success)
        assertFalse(LlmDiagnosisResult.fromReply("   ", 120).success)
    }

    @Test
    fun `reply is trimmed`() {
        assertEquals("hello", LlmDiagnosisResult.fromReply("  hello  ", 120).reply)
    }
}
