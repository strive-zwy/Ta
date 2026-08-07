package com.agent.ta.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CommitmentRetryPolicyTest {
    @Test
    fun `third failed attempt becomes failed`() {
        assertEquals("pending", CommitmentRetryPolicy.statusAfterFailure(0))
        assertEquals("pending", CommitmentRetryPolicy.statusAfterFailure(1))
        assertEquals("failed", CommitmentRetryPolicy.statusAfterFailure(2))
    }

    @Test
    fun `retry delay grows with attempts`() {
        assertEquals(60_000L, CommitmentRetryPolicy.delayMs(1))
        assertEquals(300_000L, CommitmentRetryPolicy.delayMs(2))
        assertEquals(900_000L, CommitmentRetryPolicy.delayMs(3))
    }
}
