package com.agent.ta.service

import com.agent.ta.data.local.entity.CommitmentEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CommitmentSchedulePolicyTest {
    @Test
    fun `only future pending commitments are rescheduled`() {
        val now = 1_000L
        val futurePending = commitment(id = 1L, status = "pending", triggerAt = 2_000L)
        val expiredPending = commitment(id = 2L, status = "pending", triggerAt = 500L)
        val missingTrigger = commitment(id = 3L, status = "pending", triggerAt = null)
        val completed = commitment(id = 4L, status = "completed", triggerAt = 2_000L)

        val result = CommitmentSchedulePolicy.forReschedule(
            listOf(futurePending, expiredPending, missingTrigger, completed),
            now
        )

        assertEquals(listOf(futurePending), result)
    }

    private fun commitment(
        id: Long,
        status: String,
        triggerAt: Long?
    ) = CommitmentEntity(
        id = id,
        agentId = 1L,
        type = "reminder",
        content = "测试提醒",
        participants = "user",
        triggerAt = triggerAt,
        deadline = null,
        status = status,
        source = "manual",
        relatedMessageId = null
    )
}
