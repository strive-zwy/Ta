package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agent.ta.data.local.entity.FirstMeetingStateEntity

/**
 * 首次见面状态 DAO
 *
 * 按 agentId 隔离，每个 Agent 独立维护首次见面进度。
 */
@Dao
interface FirstMeetingStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FirstMeetingStateEntity)

    @Query("SELECT * FROM first_meeting_state WHERE agentId = :agentId")
    suspend fun getByAgentId(agentId: Long): FirstMeetingStateEntity?

    @Query("SELECT * FROM first_meeting_state WHERE agentId = :agentId")
    fun observeByAgentId(agentId: Long): kotlinx.coroutines.flow.Flow<FirstMeetingStateEntity?>

    /**
     * 条件更新：仅当当前 phase 为 [fromPhase] 时才更新为 [toPhase]。
     * 返回受影响行数，1 表示成功抢占，0 表示并发竞争失败。
     * 用于 NOT_STARTED → GREETING_IN_PROGRESS 的并发抢占。
     */
    @Query(
        "UPDATE first_meeting_state SET phase = :toPhase, updatedAt = :updatedAt " +
            "WHERE agentId = :agentId AND phase = :fromPhase"
    )
    suspend fun updatePhaseIf(
        agentId: Long,
        fromPhase: String,
        toPhase: String,
        updatedAt: Long
    ): Int

    @Query("UPDATE first_meeting_state SET phase = :phase, updatedAt = :updatedAt WHERE agentId = :agentId")
    suspend fun updatePhase(agentId: Long, phase: String, updatedAt: Long)

    @Query(
        "UPDATE first_meeting_state SET greetingMessageId = :messageId, greetingSentAt = :sentAt, " +
            "phase = :phase, updatedAt = :updatedAt WHERE agentId = :agentId"
    )
    suspend fun updateGreeting(
        agentId: Long,
        messageId: Long,
        sentAt: Long,
        phase: String,
        updatedAt: Long
    )

    @Query("DELETE FROM first_meeting_state WHERE agentId = :agentId")
    suspend fun deleteByAgentId(agentId: Long)
}
