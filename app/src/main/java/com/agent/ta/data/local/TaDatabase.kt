package com.agent.ta.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.agent.ta.data.local.dao.AgentConfigDao
import com.agent.ta.data.local.dao.ChatMessageDao
import com.agent.ta.data.local.dao.CommitmentDao
import com.agent.ta.data.local.dao.ConversationSummaryDao
import com.agent.ta.data.local.dao.DailyScheduleDao
import com.agent.ta.data.local.dao.DailyStateDao
import com.agent.ta.data.local.dao.EmotionalStateDao
import com.agent.ta.data.local.dao.FutureEventDao
import com.agent.ta.data.local.dao.MemoryDao
import com.agent.ta.data.local.dao.MilestoneEventDao
import com.agent.ta.data.local.dao.OnboardingStateDao
import com.agent.ta.data.local.dao.RelationshipStateDao
import com.agent.ta.data.local.dao.StateLogDao
import com.agent.ta.data.local.entity.AgentConfigEntity
import com.agent.ta.data.local.entity.ChatMessageEntity
import com.agent.ta.data.local.entity.CommitmentEntity
import com.agent.ta.data.local.entity.ConversationSummaryEntity
import com.agent.ta.data.local.entity.DailyScheduleEntity
import com.agent.ta.data.local.entity.DailyStateEntity
import com.agent.ta.data.local.entity.EmotionalStateEntity
import com.agent.ta.data.local.entity.FutureEventEntity
import com.agent.ta.data.local.entity.MemoryEntity
import com.agent.ta.data.local.entity.MilestoneEventEntity
import com.agent.ta.data.local.entity.OnboardingStateEntity
import com.agent.ta.data.local.entity.RelationshipStateEntity
import com.agent.ta.data.local.entity.StateLogEntity

@Database(
    entities = [
        AgentConfigEntity::class,
        ChatMessageEntity::class,
        StateLogEntity::class,
        MemoryEntity::class,
        OnboardingStateEntity::class,
        DailyScheduleEntity::class,
        FutureEventEntity::class,
        ConversationSummaryEntity::class,
        DailyStateEntity::class,
        CommitmentEntity::class,
        RelationshipStateEntity::class,
        MilestoneEventEntity::class,
        EmotionalStateEntity::class
    ],
    version = 14,
    exportSchema = false
)
abstract class TaDatabase : RoomDatabase() {
    abstract fun agentConfigDao(): AgentConfigDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun stateLogDao(): StateLogDao
    abstract fun memoryDao(): MemoryDao
    abstract fun onboardingStateDao(): OnboardingStateDao
    abstract fun dailyScheduleDao(): DailyScheduleDao
    abstract fun futureEventDao(): FutureEventDao
    abstract fun conversationSummaryDao(): ConversationSummaryDao
    abstract fun dailyStateDao(): DailyStateDao
    abstract fun commitmentDao(): CommitmentDao
    abstract fun relationshipStateDao(): RelationshipStateDao
    abstract fun milestoneEventDao(): MilestoneEventDao
    abstract fun emotionalStateDao(): EmotionalStateDao

    companion object {
        const val DATABASE_NAME = "ta_database.db"
    }
}
