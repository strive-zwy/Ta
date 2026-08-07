package com.agent.ta.domain

object CommitmentRetryPolicy {
    fun statusAfterFailure(previousRetryCount: Int): String =
        if (previousRetryCount + 1 >= 3) "failed" else "pending"

    fun delayMs(retryCount: Int): Long = when (retryCount) {
        1 -> 60_000L
        2 -> 300_000L
        else -> 900_000L
    }
}
