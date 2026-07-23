package com.agent.ta.di

import com.agent.ta.TaApplication
import com.agent.ta.data.local.dao.AgentConfigDao
import com.agent.ta.data.local.dao.ChatMessageDao
import com.agent.ta.data.local.dao.DailyScheduleDao
import com.agent.ta.data.local.dao.FutureEventDao
import com.agent.ta.data.local.dao.MemoryDao
import com.agent.ta.data.local.dao.OnboardingStateDao
import com.agent.ta.data.local.dao.StateLogDao
import com.agent.ta.data.prefs.UserPreferences
import com.agent.ta.data.remote.LlmClient
import com.agent.ta.data.remote.TtsClient
import com.agent.ta.domain.AgentConfigEditor
import com.agent.ta.domain.AgentConfigProvider

/**
 * 手动依赖容器，替代 Hilt/Dagger
 */
object ServiceLocator {

    private val app: TaApplication
        get() = TaApplication.instance

    val database by lazy { app.database }

    val agentConfigDao: AgentConfigDao
        get() = database.agentConfigDao()

    val chatMessageDao: ChatMessageDao
        get() = database.chatMessageDao()

    val stateLogDao: StateLogDao
        get() = database.stateLogDao()

    val memoryDao: MemoryDao
        get() = database.memoryDao()

    val onboardingStateDao: OnboardingStateDao
        get() = database.onboardingStateDao()

    val dailyScheduleDao: DailyScheduleDao
        get() = database.dailyScheduleDao()

    val futureEventDao: FutureEventDao
        get() = database.futureEventDao()

    val userPreferences: UserPreferences by lazy {
        UserPreferences(app)
    }

    val llmClient: LlmClient by lazy { LlmClient() }

    val ttsClient: TtsClient by lazy { TtsClient() }

    val agentConfigProvider: AgentConfigProvider by lazy { AgentConfigProvider() }

    val agentConfigEditor: AgentConfigEditor by lazy { AgentConfigEditor() }
}
