# Agent Architecture v2 设计方案

**分支**: `refactor/agent-architecture-v2`（基于 `refactor/consistency-architecture`）
**创建日期**: 2026-07-27
**目标**: 对本项目进行彻底的架构分层重构，使 Agent 表现效果达到最优

---

## 一、设计动机

### 1.1 当前问题

参考 MochiBot 项目（`github.com/shikidmsh-rgb/mochibot`）和本项目的实际表现，发现以下核心问题：

1. **状态数据流向单一**：观察者数据（活动状态、时间、近期对话）只走心跳路径，未注入主回复路径，导致 LLM 被动回复时错失活动状态，产生跨轮矛盾（如"还有十五分钟结束"与"我去洗澡了"前后不一致）。
2. **记忆系统扁平**：所有记忆平等存储，长对话后关键事实（用户名字、关系、禁忌）被新记忆挤掉，Agent 表现"健忘"。
3. **主动发起无否决权**：BoredInitiator 等直接触发回复，缺少 Think 阶段判断"是否适合打扰用户"，可能产生骚扰式连续消息。
4. **长对话 Token 爆炸**：对话历史线性增长，无摘要压缩机制，长会话后期 Token 占用大且上下文易丢失。
5. **现有代码东拼西凑**：ActivityAnchor、StateMachine、BoredInitiator 等散落在不同包，缺乏明确层间契约。

### 1.2 改造目标

- 统一状态数据流：Observer 框架让被动回复和主动发起共享数据源
- 分层记忆系统：core_memory 永驻 + memory_items 召回 + raw_history 兜底
- Think/Act 解耦：主动发起前先 Think 判断时机，可 `[SKIP]` 否决
- 对话摘要分桶：长对话自动压缩，节省 Token 且保持上下文连贯
- 4层架构清晰边界：每层职责单一，接口明确，便于维护扩展

### 1.3 与方案对比

经过三个方案对比（架构分层重构 / 完全重写核心层 / 渐进式补充），选定**架构分层重构**：
- **效果最优**：4层架构让 Observer/Heartbeat/Summary/ThinkAct 成为架构公民，数据流统一
- **风险最低**：保留已完成的阶段3（ActivityAnchor + 一致性校验 + Zone A/B/C）作为骨架
- **彻底性满足**：不是补丁，而是重新定义层间契约，现有代码按归属层重构归位

---

## 二、整体架构（4层）

```
┌─────────────────────────────────────────────────────┐
│  L3 执行层 (Execution Layer)                         │
│  ChatInteractor · Tools · LlmClient · TtsClient     │
│  AgentEngine · AgentForegroundService               │
├─────────────────────────────────────────────────────┤
│  L2 认知层 (Cognitive Layer)                         │
│  PromptBuilder · ThinkActDecider ·                  │
│  ConversationSummarizer · ReplyConsistencyValidator │
├─────────────────────────────────────────────────────┤
│  L1 状态层 (State Layer)                             │
│  ActivityAnchorManager · StateMachine · MemoryStore │
│  (三层记忆: core_memory / memory_items / raw)        │
├─────────────────────────────────────────────────────┤
│  L0 基础设施层 (Infrastructure Layer)                │
│  ObserverRegistry · Heartbeat · TimeContext         │
└─────────────────────────────────────────────────────┘
```

### 2.1 层间规则

- **依赖方向**：上层依赖下层，禁止反向依赖。L0 不感知 L1/L2/L3。
- **同层协作**：同层组件通过明确接口协作，不直接调用内部实现。
- **跨层通信**：通过 ServiceLocator 注入依赖，不使用静态全局状态（除不可变常量）。

---

## 三、各层详细设计

### 3.1 L0 基础设施层

#### Observer 接口

```kotlin
interface Observer {
    /** 观察者唯一标识 */
    val id: String

    /**
     * 收集当前状态快照
     * 每次 Heartbeat tick 调用，返回当前观察到的数据
     */
    suspend fun collect(): ObserverSnapshot

    /**
     * 增量检测：与上次快照对比是否有变化
     * - true: 有变化，需要通知订阅者
     * - false: 无变化，跳过后续处理（省 LLM 调用）
     */
    fun hasDelta(current: ObserverSnapshot, previous: ObserverSnapshot?): Boolean
}

data class ObserverSnapshot(
    val observerId: String,
    val timestamp: Long,
    val data: Map<String, Any>,  // 结构化数据
    val promptHint: String       // 可直接注入 prompt 的文本
)
```

#### ObserverRegistry

```kotlin
class ObserverRegistry {
    private val observers = mutableListOf<Observer>()
    private val lastSnapshots = mutableMapOf<String, ObserverSnapshot>()

    fun register(observer: Observer)
    fun unregister(observerId: String)

    /**
     * 收集所有观察者的快照
     * 仅返回 has_delta 为 true 的观察者数据
     */
    suspend fun collectChanged(): List<ObserverSnapshot>

    /**
     * 收集所有观察者的完整快照（不筛 delta）
     * 用于主回复路径，确保 LLM 看到完整上下文
     */
    suspend fun collectAll(): List<ObserverSnapshot>
}
```

**关键设计**：`collectAll()` 用于被动回复路径，让 LLM 始终看到完整状态；`collectChanged()` 用于心跳路径，仅在变化时触发 Think 评估。

#### 内置观察者

| 观察者 | 职责 | 触发场景 |
|--------|------|---------|
| `ActivityAnchorObserver` | 监控活动锚点过期/变化 | 锚点过期时派生新锚点，状态切换时通知 |
| `TimeContextObserver` | 监控时段切换 | 跨时段时更新 StateMachine，跨天时重新生成作息 |
| `RecentConversationObserver` | 监控用户长时间未响应 | 超过阈值（如 30 分钟）触发关怀消息评估 |

#### Heartbeat

```kotlin
class Heartbeat(
    private val registry: ObserverRegistry,
    private val thinkActDecider: ThinkActDecider,
    private val scope: CoroutineScope
) {
    private var tickJob: Job?

    /** 启动心跳，每 [intervalMs] 毫秒 tick 一次 */
    fun start(intervalMs: Long = 60_000L)

    /** 停止心跳 */
    fun stop()

    private suspend fun tick() {
        val changed = registry.collectChanged()
        if (changed.isEmpty()) return

        // 1. 处理状态变化（如锚点过期）
        handleStateChanges(changed)

        // 2. 评估是否主动发起
        val decision = thinkActDecider.think(changed)
        if (decision != ThinkResult.SKIP) {
            thinkActDecider.act(decision)
        }
    }
}
```

#### TimeContext

```kotlin
class TimeContext {
    /** 统一时区：Asia/Shanghai */
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    fun now(): LocalDateTime
    fun nowMillis(): Long
    fun formatDate(time: Long): String
    fun formatTime(time: Long): String
    fun isSameDay(t1: Long, t2: Long): Boolean
    fun minutesUntil(target: LocalDateTime): Long
}
```

### 3.2 L1 状态层

#### ActivityAnchorManager（已存在，归层）

保留现有实现，增强：
- 实现 `ActivityAnchorObserver` 接口，注册到 `ObserverRegistry`
- 锚点过期时通过 Observer 通知，而非被动查询

#### StateMachine（已存在，归层）

保留现有实现，移入 `state/machine/` 包。
- `TimeContextObserver` 在时段切换时调用 `StateMachine.transitionTo()`

#### MemoryStore（新建）

```kotlin
class MemoryStore(
    private val memoryDao: MemoryDao,
    private val embeddingService: EmbeddingService  // 可选，用于召回
) {
    /**
     * 三层记忆系统
     * - core_memory: 始终注入 prompt（身份/关系/关键事实），importance >= 8
     * - memory_items: 带重要度 + embedding 召回 + 冷却时间，importance 4-7
     * - raw_history: 原始对话历史（不在 MemoryStore，由 ChatInteractor 直接查 ChatMessageDao）
     */
    suspend fun getCoreMemory(): List<MemoryEntity>
    suspend fun getRecentItems(limit: Int = 10): List<MemoryEntity>
    suspend fun recall(query: String, limit: Int = 5): List<MemoryEntity>
    suspend fun addMemory(update: MemoryUpdate, source: String)
    suspend fun promoteToCore(memoryId: Long)  // 重要度提升
}
```

**记忆分级规则**：
- `importance >= 8`：core_memory（永驻 prompt）
- `importance 4-7`：memory_items（按需召回）
- `importance <= 3`：仅入库不主动注入

### 3.3 L2 认知层

#### PromptBuilder（已存在，增强 Zone B）

Zone B 增强：注入对话摘要 + 观察者数据 + 三层记忆快照。

```kotlin
private fun buildZoneB(
    sb: StringBuilder,
    config: AgentConfig,
    state: AgentState,
    userNickname: String,
    memories: List<MemoryEntity>,
    todaySchedule: List<DailySlot>,
    observerSnapshots: List<ObserverSnapshot>,  // 新增
    conversationSummary: String?                 // 新增
) {
    sb.appendLine("═══ Zone B: 上下文参考 ═══")
    // 身份详情（已有）
    // 三层记忆（增强：标注 core/items）
    // 对话摘要（新增）
    if (conversationSummary != null) {
        sb.appendLine("【之前聊过】$conversationSummary")
    }
    // 观察者数据（新增）
    observerSnapshots.forEach { snapshot ->
        sb.appendLine(snapshot.promptHint)
    }
    // 今日作息（已有）
}
```

#### ConversationSummarizer（新建）

```kotlin
class ConversationSummarizer(
    private val llmClient: LlmClient,
    private val summaryDao: ConversationSummaryDao,
    private val scope: CoroutineScope
) {
    companion object {
        const val CONV_SUMMARY_BUCKET_SIZE = 20  // 每 20 条消息一个桶
        const val SUMMARY_MAX_LENGTH = 150       // 摘要最长 150 字
    }

    private val l1Cache = mutableMapOf<Long, String>()  // bucketId -> summary

    /**
     * 获取当前桶之前的所有桶摘要合并文本
     * 用于注入 Zone B
     */
    suspend fun getPriorSummaries(currentBucketId: Long): String?

    /**
     * 检查并生成新桶的摘要
     * 当消息数达到桶大小时触发
     */
    suspend fun checkAndGenerateSummary(messages: List<ChatMessageEntity>)

    /**
     * 后台预热：预生成下一个桶的摘要
     */
    suspend fun prewarmNextBucket()

    /**
     * L1 缓存优先，未命中查 DB（L2），DB 未命中时调用 LLM 生成并持久化
     */
    private suspend fun getOrCreateSummary(bucketId: Long, messages: List<ChatMessageEntity>): String
}
```

#### ThinkActDecider（新建）

```kotlin
class ThinkActDecider(
    private val llmClient: LlmClient,
    private val promptBuilder: PromptBuilder,
    private val prefs: UserPreferences
) {
    data class ThinkResult(
        val shouldAct: Boolean,
        val topic: String = "",
        val reason: String = "",
        val findings: String = ""
    ) {
        companion object {
            val SKIP = ThinkResult(shouldAct = false)
        }
    }

    /**
     * Think 阶段：无状态扫描，输出 JSON findings
     * - 输入：观察者数据 + today_proactive_sent + prior_attempts
     * - 输出：ThinkResult（含 [SKIP] 否决权）
     */
    suspend fun think(observerSnapshots: List<ObserverSnapshot>): ThinkResult

    /**
     * Act 阶段：基于 persona 呈现 findings
     * - 输入：ThinkResult + AgentConfig + 当前状态
     * - 输出：最终回复文本
     */
    suspend fun act(thinkResult: ThinkResult): String
}
```

**Think Prompt 设计**：
```
你是 Agent 的 Think 模块。基于以下观察数据判断是否适合主动发起对话。

【观察数据】
{observer_data}

【今日已主动发起次数】{today_proactive_sent}
【最近尝试】{prior_attempts}

【决策规则】
- 如果用户 30 分钟内发过消息，且当前无特别话题，输出 [SKIP]
- 如果 prior_attempts 失败 2 次以上，输出 [SKIP]
- 适合主动发起的场景：用户长时间未响应、跨时段切换、特殊事件

输出 JSON：{"should_act": true/false, "topic": "...", "reason": "..."}
```

#### ReplyConsistencyValidator（已存在，归层）

保留现有实现，移入 `cognitive/consistency/` 包。

### 3.4 L3 执行层

#### ChatInteractor（已存在，瘦身）

主要改造：
1. **下沉 Prompt 构造**：Zone B 注入摘要 + 观察者数据，逻辑由 PromptBuilder 承担
2. **下沉一致性校验**：保留重试循环，但校验细节由 ReplyConsistencyValidator 承担
3. **下沉记忆管理**：通过 `MemoryStore` 统一操作
4. **下沉对话摘要**：调用 `ConversationSummarizer.checkAndGenerateSummary()`
5. **保留**：用户消息入库、延迟管理、TTS 合成、通知发送

#### AgentEngine（已存在，保留）

作为 L3 入口，启动时初始化 Heartbeat 和 ObserverRegistry。

---

## 四、核心数据流

### 4.1 被动回复流（用户发消息）

```
用户消息
  ↓
ChatInteractor (L3)
  ↓
  ├─→ L1: ActivityAnchorManager.getEffectiveAnchor()
  ├─→ L1: MemoryStore.getCoreMemory() + getRecentItems()
  ├─→ L0: ObserverRegistry.collectAll()  // 完整快照
  └─→ L2: ConversationSummarizer.getPriorSummaries()
  ↓
L2: PromptBuilder.build(Zone A/B/C)
    Zone A: 时间锚#1 + ActivityAnchor + 身份核心
    Zone B: 三层记忆 + 对话摘要 + 观察者数据 + 今日作息
    Zone C: 场景 + 一致性规则 + 输出格式 + 时间锚#2
  ↓
L3: LlmClient 调用（工具调用循环，max 3 轮）
  ↓
L2: ReplyConsistencyValidator 校验 → 失败追加修正指令重试(max 2)
  ↓
L3: TtsClient 合成 + 入库 + 通知
  ↓
  ├─→ L1: MemoryStore.addMemory()
  ├─→ L1: ActivityAnchorManager 更新（若 LLM 调用 set_activity）
  └─→ L2: ConversationSummarizer.checkAndGenerateSummary()
  ↓
L0: ObserverRegistry.publishStateChange()  // 通知观察者状态变化
```

### 4.2 主动发起流（Heartbeat 触发）

```
Heartbeat tick (L0, 每分钟)
  ↓
L0: ObserverRegistry.collectChanged()
  ↓
has_delta = false → 跳过（省 LLM 调用）
  ↓
has_delta = true
  ↓
  ├─→ L0: 处理状态变化（如 ActivityAnchorObserver 派生新锚点）
  └─→ L2: ThinkActDecider.think()
  ↓
ThinkResult.SKIP → 不发起
  ↓
ThinkResult(shouldAct=true, topic=...)
  ↓
L2: ThinkActDecider.act()
  ↓
L3: 入库 + TTS + 通知
  ↓
L1: 更新 today_proactive_sent
```

### 4.3 心跳流（每分钟）

```
Heartbeat tick
  ↓
ObserverRegistry.tickAll()
  ├─→ ActivityAnchorObserver: 检测锚点过期 → 派生新锚点 → has_delta=true
  ├─→ TimeContextObserver: 检测时段切换 → 更新 StateMachine → has_delta=true
  └─→ RecentConversationObserver: 检测用户长时间未响应 → has_delta=true
  ↓
collectChanged() 返回有变化的快照
  ↓
触发 ThinkActDecider 评估
```

---

## 五、与现有代码的映射

| 现有文件 | 归属层 | 改造动作 |
|---------|--------|---------|
| `domain/anchor/ActivityAnchor.kt` | L1 | 保留，移入 `state/anchor/` |
| `domain/anchor/ActivityAnchorManager.kt` | L1 | 保留，移入 `state/anchor/`，实现 Observer 接口 |
| `service/StateMachine.kt` | L1 | 保留，移入 `state/machine/` |
| `domain/consistency/ReplyConsistencyValidator.kt` | L2 | 保留，移入 `cognitive/consistency/` |
| `domain/PromptBuilder.kt` | L2 | 保留，增强 Zone B（注入摘要+观察者数据） |
| `domain/ChatInteractor.kt` | L3 | **瘦身**：业务逻辑下沉到 L1/L2 |
| `di/ServiceLocator.kt` | 跨层 | 增强：注册 Observer/Heartbeat/Summarizer |
| `service/AgentEngine.kt` | L3 | 保留，作为 L3 入口，启动 Heartbeat |
| `service/BoredInitiator.kt` | L3 | **重构**：改为 ThinkActDecider 的 Act 阶段实现 |
| `data/local/dao/MemoryDao.kt` | L1 | 保留，被 MemoryStore 封装 |
| `data/local/entity/MemoryEntity.kt` | L1 | 保留 |
| **(新增)** `infrastructure/observer/Observer.kt` | L0 | 新建 |
| **(新增)** `infrastructure/observer/ObserverRegistry.kt` | L0 | 新建 |
| **(新增)** `infrastructure/observer/ActivityAnchorObserver.kt` | L0 | 新建 |
| **(新增)** `infrastructure/observer/TimeContextObserver.kt` | L0 | 新建 |
| **(新增)** `infrastructure/observer/RecentConversationObserver.kt` | L0 | 新建 |
| **(新增)** `infrastructure/heartbeat/Heartbeat.kt` | L0 | 新建 |
| **(新增)** `infrastructure/time/TimeContext.kt` | L0 | 新建 |
| **(新增)** `state/memory/MemoryStore.kt` | L1 | 新建 |
| **(新增)** `cognitive/summary/ConversationSummarizer.kt` | L2 | 新建 |
| **(新增)** `cognitive/summary/SummaryBucket.kt` | L2 | 新建 |
| **(新增)** `cognitive/thinkact/ThinkActDecider.kt` | L2 | 新建 |
| **(新增)** `data/local/dao/ConversationSummaryDao.kt` | L1 | 新建 |
| **(新增)** `data/local/entity/ConversationSummaryEntity.kt` | L1 | 新建 |

---

## 六、目录结构（改造后）

```
com.agent.ta/
├── infrastructure/          # L0 基础设施层
│   ├── observer/
│   │   ├── Observer.kt              # 接口 + ObserverSnapshot
│   │   ├── ObserverRegistry.kt
│   │   ├── ActivityAnchorObserver.kt
│   │   ├── TimeContextObserver.kt
│   │   └── RecentConversationObserver.kt
│   ├── heartbeat/
│   │   └── Heartbeat.kt
│   └── time/
│       └── TimeContext.kt
├── state/                   # L1 状态层
│   ├── anchor/
│   │   ├── ActivityAnchor.kt
│   │   └── ActivityAnchorManager.kt
│   ├── machine/
│   │   └── StateMachine.kt
│   └── memory/
│       ├── MemoryStore.kt
│       └── MemoryItem.kt
├── cognitive/               # L2 认知层
│   ├── prompt/
│   │   └── PromptBuilder.kt
│   ├── summary/
│   │   ├── ConversationSummarizer.kt
│   │   └── SummaryBucket.kt
│   ├── thinkact/
│   │   ├── ThinkActDecider.kt
│   │   ├── ThinkScanner.kt
│   │   └── ActPresenter.kt
│   └── consistency/
│       └── ReplyConsistencyValidator.kt
├── execution/               # L3 执行层
│   ├── ChatInteractor.kt
│   ├── tool/
│   │   ├── builtin/
│   │   └── ...
│   └── remote/
│       └── ...
├── service/                 # 系统服务（跨层）
│   ├── AgentEngine.kt
│   ├── AgentForegroundService.kt
│   ├── BootReceiver.kt
│   ├── LifeEventInitiator.kt
│   ├── NotificationHelper.kt
│   └── StateScheduler.kt
├── data/                    # 数据持久化（跨层）
│   ├── local/
│   │   ├── dao/
│   │   ├── entity/
│   │   └── TaDatabase.kt
│   ├── model/
│   ├── prefs/
│   └── remote/
├── di/
│   └── ServiceLocator.kt
├── ui/
│   └── ...
└── util/
```

**注意**：`domain/` 包将被拆解，原有文件分别归入 `state/`、`cognitive/`、`execution/`。`data/` 和 `service/` 保持原位（它们是 Android 框架约定的包）。

---

## 七、实施阶段

| 阶段 | 内容 | 验证方式 | 预估文件数 |
|------|------|---------|-----------|
| **阶段4** | L0 基础设施：Observer + Heartbeat + 3个内置观察者 + TimeContext | 编译 + Heartbeat 日志可见 + Observer 注册成功 | ~7 新建 |
| **阶段5** | L1 状态层：MemoryStore 三层记忆 + Anchor 接入 Observer + 目录重组 | 编译 + 记忆分级日志 + Observer 触发锚点更新 | ~3 新建 + 重组 |
| **阶段6** | L2 认知层：ConversationSummarizer + ThinkActDecider + PromptBuilder Zone B 增强 | 编译 + 摘要生成 + 主动发起 Think/Act | ~5 新建 |
| **阶段7** | L3 集成 + ChatInteractor 瘦身 + BoredInitiator 重构 + 端到端验证 | 编译 + 运行 + 一致性场景测试 | ~2 重构 |

**每个阶段独立提交**，确保可回滚。

---

## 八、关键设计决策

### 8.1 为什么 Observer 同时支持 collectAll() 和 collectChanged()？

- `collectAll()`：被动回复路径使用，LLM 需要看到完整当前状态（即使无变化）
- `collectChanged()`：心跳路径使用，仅在变化时触发 Think，节省 LLM 调用

### 8.2 为什么 BoredInitiator 改为 ThinkActDecider 的 Act？

现有 BoredInitiator 直接生成回复，缺少 Think 阶段判断时机。重构后：
- Think 阶段：评估"是否适合打扰用户"（用户 30 分钟内发过消息、prior_attempts 失败 2 次等）
- Act 阶段：基于 persona 呈现话题

### 8.3 为什么对话摘要用分桶而非滑动窗口？

- 分桶：每 20 条消息生成一个摘要，摘要独立持久化，可精确召回历史任意时间段
- 滑动窗口：摘要随窗口滚动丢失早期信息，长会话后期无法回溯

### 8.4 为什么 MemoryStore 不直接管理 raw_history？

- raw_history 是原始对话记录，由 ChatMessageDao 管理
- MemoryStore 专注 core_memory 和 memory_items 的分级与召回
- ChatInteractor 直接查 ChatMessageDao 拼接近期历史，与 MemoryStore 协作

### 8.5 为什么保留 ServiceLocator 而非改用 Hilt/Dagger？

- 现有项目已使用 ServiceLocator 模式，改用 DI 框架会引入额外复杂度
- ServiceLocator 满足当前需求，改造后职责更清晰（注册 L0/L1/L2 组件）
- 后续若需要更严格的 DI，可平滑迁移

---

## 九、风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| 改造期间功能回归 | 每个阶段独立提交 + 编译验证 + 不删除旧代码（仅移动） |
| Observer 死循环 | Heartbeat tick 间隔 60 秒，Observer.hasDelta 严格比较避免误判 |
| 摘要生成失败 | LLM 调用失败时降级为截断前 50 字，不阻塞主流程 |
| 记忆系统迁移丢数据 | MemoryStore 基于 MemoryDao，不修改表结构，仅增加 importance 字段查询 |
| 目录重组导致大量 import 变更 | 分阶段重组，每个阶段只移动相关包 |

---

## 十、成功标准

1. **编译通过**：所有阶段完成后，`./gradlew assembleDebug` 成功
2. **功能完整**：原有功能（聊天、状态机、TTS、工具调用、配置）全部正常
3. **架构清晰**：4层包结构明确，层间依赖无反向
4. **表现提升**：
   - Agent 回复内容与当前活动状态一致（解决"还有十五分钟结束"与"我去洗澡了"矛盾）
   - 长对话后仍记得用户关键信息（如名字、关系）
   - 主动发起不再骚扰用户（Think 阶段 [SKIP] 生效）
   - 长对话 Token 占用降低（摘要分桶生效）

---

## 附录 A：MochiBot 参考点

| MochiBot 概念 | 本项目对应 | 改造状态 |
|--------------|-----------|---------|
| Observer Pattern | ObserverRegistry + Observer 接口 | 阶段4 新建 |
| Heartbeat | Heartbeat 类 | 阶段4 新建 |
| Core Memory | MemoryStore.getCoreMemory() | 阶段5 新建 |
| Memory Items | MemoryStore.getRecentItems() + recall() | 阶段5 新建 |
| Conversation Summary | ConversationSummarizer | 阶段6 新建 |
| Think/Act Decoupling | ThinkActDecider | 阶段6 新建 |
| Activity Anchor | ActivityAnchorManager（已存在） | 阶段3 完成 |
| Reply Consistency | ReplyConsistencyValidator（已存在） | 阶段3 完成 |
| Zone A/B/C Prompt | PromptBuilder（已存在） | 阶段3 完成 |

## 附录 B：已完成的阶段3成果

阶段3（refactor/consistency-architecture 分支）已实现：
- `ActivityAnchor` + `ActivityAnchorManager`：活动锚点基础设施
- `ReplyConsistencyValidator`：回复一致性校验
- `PromptBuilder` Zone A/B/C 三段架构 + 双时间锚定
- `SetActivityTool`：LLM 通过 function calling 设置活动锚点
- `ChatInteractor` 集成校验重试循环（最多 2 次）

这些成果作为 v2 架构的骨架，在阶段4-7中归位到对应层。
