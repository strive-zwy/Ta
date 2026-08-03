package com.agent.ta.di

import com.agent.ta.TaApplication
import com.agent.ta.data.local.dao.AgentConfigDao
import com.agent.ta.data.local.dao.ChatMessageDao
import com.agent.ta.data.local.dao.CommitmentDao
import com.agent.ta.data.local.dao.EmotionalStateDao
import com.agent.ta.data.local.dao.MilestoneEventDao
import com.agent.ta.data.local.dao.RelationshipStateDao
import com.agent.ta.data.local.dao.DailyScheduleDao
import com.agent.ta.data.local.dao.DailyStateDao
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
import com.agent.ta.domain.tool.builtin.CreateCommitmentTool
import com.agent.ta.infrastructure.heartbeat.Heartbeat
import com.agent.ta.infrastructure.observer.ActivityAnchorObserver
import com.agent.ta.infrastructure.observer.CommitmentObserver
import com.agent.ta.infrastructure.observer.ObserverRegistry
import com.agent.ta.infrastructure.observer.RecentConversationObserver
import com.agent.ta.infrastructure.observer.TimeContextObserver
import com.agent.ta.infrastructure.time.TimeContext
import com.agent.ta.state.memory.MemoryStore
import com.agent.ta.cognitive.summary.ConversationSummarizer
import com.agent.ta.cognitive.thinkact.ThinkActDecider

/**
 * 手动依赖容器，替代 Hilt/Dagger
 *
 * 分层注册（v2 架构）：
 * - L0 基础设施层: observerRegistry / heartbeat / timeContext
 * - L1 状态层: activityAnchorManager / memoryStore (阶段5)
 * - L2 认知层: conversationSummarizer / thinkActDecider (阶段6)
 * - L3 执行层: chatInteractor / toolRegistry / llmClient / ttsClient
 */
object ServiceLocator {

    private val app: TaApplication
        get() = TaApplication.instance ?: throw IllegalStateException(
            "ServiceLocator accessed before TaApplication.onCreate. " +
                "If this is a ContentProvider, use lazy initialization or move access to Application.onCreate."
        )

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

    val dailyStateDao: DailyStateDao
        get() = database.dailyStateDao()

    val commitmentDao: CommitmentDao
        get() = database.commitmentDao()

    val relationshipStateDao: RelationshipStateDao
        get() = database.relationshipStateDao()

    val milestoneEventDao: MilestoneEventDao
        get() = database.milestoneEventDao()

    val emotionalStateDao: EmotionalStateDao
        get() = database.emotionalStateDao()

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

    // ═══════════════════════════════════════════════════════════════════════════
    // L0 基础设施层（v2 架构新增）
    // ═══════════════════════════════════════════════════════════════════════════

    /** 统一时间上下文（Asia/Shanghai 时区） */
    val timeContext: TimeContext by lazy { TimeContext.getInstance() }

    /**
     * 观察者注册中心
     *
     * 注册的观察者：
     * - ActivityAnchorObserver: 监控活动锚点过期/变化
     * - TimeContextObserver: 监控时段切换/跨天
     * - RecentConversationObserver: 监控用户长时间未响应
     * - CommitmentObserver: 监控到期承诺（AlarmManager 兜底）
     *
     * 使用方式：
     * - 主回复路径: registry.collectAll() 获取完整快照注入 Prompt
     * - 心跳路径: registry.collectChanged() 仅获取变化触发 Think
     */
    val observerRegistry: ObserverRegistry by lazy { ObserverRegistry() }

    /**
     * 心跳调度器（每分钟 tick）
     *
     * 启动时机：AgentEngine.start() 中调用 heartbeat.start()
     * 停止时机：通常不需要停止，前台服务存活期间持续运行
     *
     * 阶段4: 仅记录日志验证 Observer 工作
     * 阶段6: 注入 ThinkActDecider 处理状态变化
     */
    val heartbeat: Heartbeat by lazy { Heartbeat(observerRegistry, appScope) }

    /** 观察者是否已注册（避免重复注册） */
    @Volatile
    private var observersRegistered: Boolean = false

    /**
     * 注册内置观察者（首次访问时调用）
     * 在 AgentEngine.start() 中触发，确保 DB 和作息表已就绪
     */
    suspend fun registerObserversIfNeeded() {
        if (observersRegistered) return
        observersRegistered = true
        observerRegistry.register(ActivityAnchorObserver())
        observerRegistry.register(TimeContextObserver())
        observerRegistry.register(RecentConversationObserver())
        observerRegistry.register(CommitmentObserver())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // L1 状态层（v2 架构新增）
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 三层记忆系统（core_memory / memory_items / raw_history）
     *
     * - core_memory: importance >= 4，永驻 prompt
     * - memory_items: importance 2-3，按需召回
     * - raw_history: 由 ChatInteractor 直接查 ChatMessageDao
     *
     * 封装 MemoryDao，提供分级查询和召回接口
     */
    val memoryStore: MemoryStore by lazy { MemoryStore(memoryDao) }

    // ═══════════════════════════════════════════════════════════════════════════
    // L2 认知层（v2 架构新增）
    // ═══════════════════════════════════════════════════════════════════════════

    /** ConversationSummaryDao（L2 摘要持久化） */
    val conversationSummaryDao: com.agent.ta.data.local.dao.ConversationSummaryDao
        get() = database.conversationSummaryDao()

    /**
     * 对话摘要生成器（分桶机制 + L1 内存缓存 + L2 Room DB + 后台预热）
     *
     * 每 20 条消息生成一个摘要（150字内），注入 Prompt Zone B 节省 Token
     */
    val conversationSummarizer: ConversationSummarizer by lazy {
        ConversationSummarizer(llmClient, conversationSummaryDao, chatMessageDao, appScope)
    }

    /**
     * Think/Act 决策器（主动发起的 Think/Act 解耦）
     *
     * - Think 阶段：判断是否适合主动发起（含 [SKIP] 否决权）
     * - Act 阶段：基于 persona 呈现话题
     *
     * 由 Heartbeat 在状态变化时调用
     */
    val thinkActDecider: ThinkActDecider by lazy { ThinkActDecider(llmClient) }

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
     * - create_commitment：创建承诺/约定/提醒（appointment/promise/reminder，AlarmManager 调度）
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
            register(CreateCommitmentTool())
        }
    }
}
