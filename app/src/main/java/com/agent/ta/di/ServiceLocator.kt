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
import com.agent.ta.domain.tool.ToolRegistry
import com.agent.ta.domain.tool.builtin.TimeTool
import com.agent.ta.domain.tool.builtin.WeatherTool
import com.agent.ta.domain.tool.builtin.WebSearchTool
import com.agent.ta.domain.tool.builtin.TodoTool
import com.agent.ta.domain.tool.builtin.MemoryTool

/**
 * 手动依赖容器，替代 Hilt/Dagger
 */
object ServiceLocator {

    private val app: TaApplication
        get() = TaApplication.instance

    /** 应用级协程作用域，不绑定 Compose composition 生命周期 */
    val appScope: kotlinx.coroutines.CoroutineScope
        get() = app.appScope

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

    /**
     * 工具注册中心（v3 通用工具系统）
     *
     * 注册内置工具：
     * - web_search：联网搜索（DuckDuckGo HTML 接口，免费无 Key）
     * - get_weather：天气查询（Open-Meteo 免费 API）
     * - get_current_time：当前时间
     * - manage_todo：待办事项管理（add/list/complete，影响作息安排）
     * - query_memory：记忆查询（让 LLM 主动检索历史记忆）
     *
     * 自定义工具通过 .skill.zip 导入后动态注册（阶段 7 实现）
     */
    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry().apply {
            register(WebSearchTool())
            register(WeatherTool())
            register(TimeTool())
            register(TodoTool())
            register(MemoryTool())
        }
    }
}
