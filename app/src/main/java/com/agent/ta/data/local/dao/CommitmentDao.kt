package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.agent.ta.data.local.entity.CommitmentEntity

@Dao
interface CommitmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CommitmentEntity): Long

    @Update
    suspend fun update(entity: CommitmentEntity)

    @Query("SELECT * FROM commitments WHERE agentId = :agentId AND id = :id LIMIT 1")
    suspend fun getById(agentId: Long, id: Long): CommitmentEntity?

    @Query("SELECT * FROM commitments WHERE agentId = :agentId AND status = :status ORDER BY triggerAt ASC")
    suspend fun getByStatus(agentId: Long, status: String): List<CommitmentEntity>

    @Query("SELECT * FROM commitments WHERE agentId = :agentId ORDER BY createdAt ASC")
    suspend fun getAll(agentId: Long): List<CommitmentEntity>

    /**
     * 查询已到触发时间但还未触发的承诺
     * 用于 CommitmentObserver 和 AlarmManager 兜底检测
     */
    @Query("SELECT * FROM commitments WHERE agentId = :agentId AND status = 'pending' AND triggerAt IS NOT NULL AND triggerAt <= :now AND (nextRetryAt IS NULL OR nextRetryAt <= :now) ORDER BY triggerAt ASC")
    suspend fun getDueCommitments(agentId: Long, now: Long): List<CommitmentEntity>

    /**
     * 查询已超过截止时间的承诺
     * 用于超时自动过期清理
     */
    @Query("SELECT * FROM commitments WHERE agentId = :agentId AND status IN ('pending','claimed','delivered') AND deadline IS NOT NULL AND deadline < :now")
    suspend fun getExpiredCommitments(agentId: Long, now: Long): List<CommitmentEntity>

    /**
     * 查询某日期范围内的承诺（按 createdAt 时间戳过滤）
     */
    @Query("SELECT * FROM commitments WHERE agentId = :agentId AND createdAt >= :startTs AND createdAt < :endTs ORDER BY createdAt DESC")
    suspend fun getByDateRange(agentId: Long, startTs: Long, endTs: Long): List<CommitmentEntity>

    @Query("UPDATE commitments SET status = :status, updatedAt = :updatedAt WHERE agentId = :agentId AND id = :id")
    suspend fun updateStatus(agentId: Long, id: Long, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE commitments SET status = 'claimed', claimedAt = :now, updatedAt = :now WHERE agentId = :agentId AND id = :id AND status = 'pending' AND (nextRetryAt IS NULL OR nextRetryAt <= :now)")
    suspend fun claimPending(agentId: Long, id: Long, now: Long): Int

    @Query("UPDATE commitments SET status = 'delivered', claimedAt = NULL, nextRetryAt = NULL, updatedAt = :now WHERE agentId = :agentId AND id = :id AND status = 'claimed'")
    suspend fun markDelivered(agentId: Long, id: Long, now: Long): Int

    @Query("UPDATE commitments SET status = :status, claimedAt = NULL, retryCount = retryCount + 1, nextRetryAt = :nextRetryAt, updatedAt = :now WHERE agentId = :agentId AND id = :id AND status = 'claimed'")
    suspend fun releaseAfterFailure(agentId: Long, id: Long, status: String, nextRetryAt: Long?, now: Long): Int

    /**
     * 清理已完成的旧承诺（30 天前的 completed/cancelled/expired）
     */
    @Query("DELETE FROM commitments WHERE agentId = :agentId AND status IN ('completed','cancelled','expired') AND updatedAt < :beforeTs")
    suspend fun deleteOldCompleted(agentId: Long, beforeTs: Long): Int

    @Query("DELETE FROM commitments WHERE agentId = :agentId")
    suspend fun deleteAll(agentId: Long)
}
