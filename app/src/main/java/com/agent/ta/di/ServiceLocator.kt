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
import com.agent.ta.domain.anchor.ActivityAnchorManager
import com.agent.ta.domain.tool.ToolRegistry
import com.agent.ta.domain.tool.builtin.TimeTool
import com.agent.ta.domain.tool.builtin.WeatherTool
import com.agent.ta.domain.tool.builtin.WebSearchTool
import com.agent.ta.domain.tool.builtin.TodoTool
import com.agent.ta.domain.tool.builtin.MemoryTool
import com.agent.ta.domain.tool.builtin.SetActivityTool

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
     * 活动锚点管理器（应用侧权威状态）
     *
     * 维护"Agent 当前正在做什么"的结构化锚点，解决 LLM 前后回复活动状态矛盾的问题。
     * - 从作息表当前时段派生默认锚点（SCHEDULE 来源）
     * - 接受 LLM 通过 set_activity 工具显式设置锚点（LLM 来源，持久化）
     * - LLM 锚点过期后自动回退到作息表派生
     */
    val activityAnchorManager: ActivityAnchorManager by lazy { ActivityAnchorManager(app) }

    /**
     * 工具注册中心（v3 通用工具系统）
     *
     * 注册内置工具：
     * - web_search：联网搜索（DuckDuckGo HTML 接口，免费无 Key）
     * - get_weather：天气查询（Open-Meteo 免费 API）
     * - get_current_time：当前时间
     * - manage_todo：待办事项管理（add/list/complete，影响作息安排）
     * - query_memory：记忆查询（让 LLM 主动检索历史记忆）
     * - set_activity：设置当前活动（LLM 显式声明活动锚点，解决前后回复矛盾）
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
            register(SetActivityTool())
        }
    }
}
