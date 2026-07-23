package com.agent.ta.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agent.ta.data.local.entity.OnboardingStateEntity

@Dao
interface OnboardingStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OnboardingStateEntity)

    @Query("SELECT * FROM onboarding_state WHERE id = 1")
    suspend fun get(): OnboardingStateEntity?

    @Query("UPDATE onboarding_state SET phase = :phase, currentStep = :step WHERE id = 1")
    suspend fun updateProgress(phase: String, step: Int)

    @Query("UPDATE onboarding_state SET phase = 'completed', completedAt = :completedAt WHERE id = 1")
    suspend fun complete(completedAt: Long)
}
