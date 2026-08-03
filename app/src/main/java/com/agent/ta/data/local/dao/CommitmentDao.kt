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

    @Query("SELECT * FROM commitments WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CommitmentEntity?

    @Query("SELECT * FROM commitments WHERE status = :status ORDER BY triggerAt ASC")
    suspend fun getByStatus(status: String): List<CommitmentEntity>

    /**
     * 查询已到触发时间但还未触发的承诺
     * 用于 CommitmentObserver 和 AlarmManager 兜底检测
     */
    @Query("SELECT * FROM commitments WHERE status = 'pending' AND triggerAt IS NOT NULL AND triggerAt <= :now ORDER BY triggerAt ASC")
    suspend fun getDueCommitments(now: Long): List<CommitmentEntity>

    /**
     * 查询已超过截止时间的承诺
     * 用于超时自动过期清理
     */
    @Query("SELECT * FROM commitments WHERE status IN ('pending','triggered') AND deadline IS NOT NULL AND deadline < :now")
    suspend fun getExpiredCommitments(now: Long): List<CommitmentEntity>

    /**
     * 查询某日期范围内的承诺（按 createdAt 时间戳过滤）
     */
    @Query("SELECT * FROM commitments WHERE createdAt >= :startTs AND createdAt < :endTs ORDER BY createdAt DESC")
    suspend fun getByDateRange(startTs: Long, endTs: Long): List<CommitmentEntity>

    @Query("UPDATE commitments SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, updatedAt: Long = System.currentTimeMillis())

    /**
     * 清理已完成的旧承诺（30 天前的 completed/cancelled/expired）
     */
    @Query("DELETE FROM commitments WHERE status IN ('completed','cancelled','expired') AND updatedAt < :beforeTs")
    suspend fun deleteOldCompleted(beforeTs: Long): Int
}
