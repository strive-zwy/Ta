package com.agent.ta.service

import com.agent.ta.data.local.entity.CommitmentEntity

object CommitmentSchedulePolicy {
    fun forReschedule(
        commitments: List<CommitmentEntity>,
        now: Long
    ): List<CommitmentEntity> =
        commitments.filter {
            it.status == "pending" &&
                it.triggerAt != null &&
                it.triggerAt > now
        }
}
