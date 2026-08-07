# Agent 首次见面与多 Agent 数据隔离 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use executing-plans to implement this plan task-by-task.

**Goal:** 为默认 Agent 和每次新导入的 Agent 建立独立数据空间，并实现按人格主动问候、称呼结构化提取、一次自然追问及后续称呼修改能力。

**Architecture:** 以 `AgentConfigEntity.id` 作为本地稳定 `agentId`，Room v15 将聊天、记忆、关系、情绪、作息、承诺等数据全部按 Agent 隔离。新增 `ActiveAgentManager` 管理实例切换，新增 `FirstMeetingCoordinator` 管理首次见面状态机，新增 `NicknameResolver` 与 `NicknameValidator` 分别承担 LLM 语义理解和本地确定性校验。所有异步请求在开始时捕获 `agentId`，结果只写回该实例，禁止完成时临时查询当前 Agent。

**Tech Stack:** Kotlin、Android、Jetpack Compose、Room 2.7.1、Kotlin Coroutines/Flow、kotlinx.serialization、AlarmManager、JUnit4、AndroidX Instrumentation Test。

**Execution policy:** 按顺序执行，每个任务先写失败测试，再实现最小代码，再运行目标测试和 `compileDebugKotlin`。除非用户明确要求，不执行 Git commit。

---

## Batch 1：测试与数据库迁移基础

### Task 1：启用 Room schema 导出和迁移测试依赖

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/src/main/java/com/agent/ta/data/local/TaDatabase.kt`
- Create: `app/schemas/.gitkeep`

**Steps:**
1. 在版本目录中增加 `androidx.room:room-testing:2.7.1` 别名。
2. 在 `app/build.gradle.kts` 增加 `androidTestImplementation(libs.androidx.room.testing)`。
3. 通过 KSP 参数配置 Room schema 输出目录为 `$projectDir/schemas`。
4. 将 `TaDatabase` 的 `exportSchema` 改为 `true`。
5. 运行 `./gradlew compileDebugKotlin --no-daemon`，预期 `BUILD SUCCESSFUL` 且生成 v14 schema。
6. 运行 `./gradlew testDebugUnitTest --no-daemon`，预期现有单元测试全部通过。

### Task 2：为 Agent 激活和实例查询建立事务 API

**Files:**
- Modify: `app/src/main/java/com/agent/ta/data/local/dao/AgentConfigDao.kt`
- Create: `app/src/main/java/com/agent/ta/domain/ActiveAgentManager.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/AgentConfigProvider.kt`
- Modify: `app/src/main/java/com/agent/ta/di/ServiceLocator.kt`
- Test: `app/src/androidTest/java/com/agent/ta/domain/ActiveAgentManagerTest.kt`

**Steps:**
1. 写仪器测试：创建两个同名 Agent，切换后断言只有目标 ID 为 active。
2. 写仪器测试：无激活记录时，按 `importedAt DESC, id DESC` 选择确定的 fallback。
3. 在 DAO 新增 `getById(id)`、`getActiveDeterministic()`、`activateById(id)`、`countActive()`。
4. 使用 `@Transaction` 实现“全部停用 + 指定 ID 激活”。
5. 新增 `ActiveAgentManager`，暴露 `activeAgentId: StateFlow<Long?>`、`ensureDefaultAgentPersisted()`、`switchTo(agentId)`、`getRequiredActiveAgentId()`。
6. 修改 `AgentConfigProvider.reload()` 同时刷新配置和实例 ID，禁止只返回内存默认 Agent。
7. 运行目标仪器测试，预期全部通过。
8. 运行 `./gradlew compileDebugKotlin --no-daemon`。

### Task 3：定义 v15 的 Agent 归属字段与首次见面实体

**Files:**
- Modify: `app/src/main/java/com/agent/ta/data/local/entity/ChatMessageEntity.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/entity/StateLogEntity.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/entity/MemoryEntity.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/entity/DailyScheduleEntity.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/entity/FutureEventEntity.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/entity/ConversationSummaryEntity.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/entity/DailyStateEntity.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/entity/CommitmentEntity.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/entity/RelationshipStateEntity.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/entity/MilestoneEventEntity.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/entity/EmotionalStateEntity.kt`
- Create: `app/src/main/java/com/agent/ta/data/local/entity/FirstMeetingStateEntity.kt`
- Create: `app/src/main/java/com/agent/ta/data/local/dao/FirstMeetingStateDao.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/TaDatabase.kt`

**Required model:**
```kotlin
@Entity(tableName = "first_meeting_state")
data class FirstMeetingStateEntity(
    @PrimaryKey val agentId: Long,
    val phase: String,
    val greetingMessageId: Long? = null,
    val greetingSentAt: Long? = null,
    val userReplyCount: Int = 0,
    val followUpAsked: Boolean = false,
    val nicknameCaptured: Boolean = false,
    val completedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
```

**Steps:**
1. 为所有业务实体增加非空 `agentId: Long`。
2. 将 `daily_schedule` 和 `daily_state` 主键改为 `primaryKeys = ["agentId", "date"]`。
3. 将关系和情绪状态改为每个 `agentId` 一条记录，不再使用全局 `id=1`。
4. 将 `future_events` 唯一索引改为 `(agentId, date, description)`。
5. 为高频查询增加组合索引：消息时间、记忆重要性、摘要 bucket、承诺状态/触发时间、里程碑类型/时间。
6. 注册 `FirstMeetingStateEntity` 和 DAO，将数据库版本提升到 15。
7. 此步骤暂时预期编译失败，因为 DAO 和调用方尚未传递 `agentId`；记录编译错误作为后续任务清单，不使用默认 `0` 掩盖问题。

### Task 4：实现并验证 Room 14→15 数据迁移

**Files:**
- Create: `app/src/main/java/com/agent/ta/data/local/DatabaseMigrations.kt`
- Modify: `app/src/main/java/com/agent/ta/TaApplication.kt`
- Test: `app/src/androidTest/java/com/agent/ta/data/local/Migration14To15Test.kt`

**Migration rules:**
1. 在迁移事务内确定旧数据归属 Agent：active 中最近导入且 ID 最大者；无 active 时选最近配置；无配置时插入默认配置。
2. 重建全部 11 张需要 `agentId` 的业务表，保留原自增 ID、时间戳和逻辑引用。
3. 旧数据统一归入迁移确定的 Agent。
4. 当前 Agent 已有聊天记录时，创建 `first_meeting_state=COMPLETED_WITHOUT_NICKNAME`；无聊天时创建 `NOT_STARTED`。
5. 其他历史 Agent 创建 `NOT_STARTED` 状态和独立默认关系/情绪记录。
6. 删除旧唯一索引，建立 Agent 维度的新索引。
7. 把迁移从 `TaApplication` 拆到 `DatabaseMigrations`，集中注册并移除 v14→v15 的破坏性兜底风险。

**Tests:**
1. 构造 v14 数据库：多个配置、旧消息、记忆、摘要、关系、情绪、作息、承诺。
2. 执行 14→15，断言行数、原 ID、消息引用和字段值不变。
3. 断言全部旧业务数据归属确定的 active agent。
4. 断言两个 Agent 可保存相同日期的作息和相同描述的未来事件。
5. 断言已有聊天的 Agent 不会升级后重新问候。
6. 运行 `./gradlew connectedDebugAndroidTest --no-daemon`，预期迁移测试通过。

---

## Batch 2：DAO 与领域服务全面按 Agent 隔离

### Task 5：改造聊天、记忆和摘要 DAO

**Files:**
- Modify: `app/src/main/java/com/agent/ta/data/local/dao/ChatMessageDao.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/dao/MemoryDao.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/dao/ConversationSummaryDao.kt`
- Modify: `app/src/main/java/com/agent/ta/state/memory/MemoryStore.kt`
- Modify: `app/src/main/java/com/agent/ta/cognitive/summary/ConversationSummarizer.kt`
- Test: `app/src/androidTest/java/com/agent/ta/data/local/AgentScopedConversationTest.kt`

**Steps:**
1. 先写测试：Agent A/B 插入消息和记忆后，各自查询只能看到自己的数据。
2. 所有 SELECT/UPDATE/DELETE 增加 `agentId` 条件；即便主键全局唯一，更新也必须使用 `WHERE agentId=:agentId AND id=:id`。
3. `observeAll(agentId)` 替换全局消息 Flow。
4. 摘要 bucket、最大 bucket、消息范围均按 Agent 计算。
5. `MemoryStore` 和 `ConversationSummarizer` 的公开方法显式传入 `agentId`。
6. 运行隔离测试和编译。

### Task 6：改造作息、状态、未来事件 DAO 与服务

**Files:**
- Modify: `app/src/main/java/com/agent/ta/data/local/dao/DailyScheduleDao.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/dao/DailyStateDao.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/dao/StateLogDao.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/dao/FutureEventDao.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/DailyPlanner.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/DailySummaryGenerator.kt`
- Modify: `app/src/main/java/com/agent/ta/service/StateMachine.kt`
- Modify: `app/src/main/java/com/agent/ta/service/StateScheduler.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/anchor/ActivityAnchorManager.kt`
- Test: `app/src/androidTest/java/com/agent/ta/data/local/AgentScopedScheduleTest.kt`

**Steps:**
1. 写测试验证同一天两个 Agent 可有不同作息和状态。
2. DAO 和服务方法全部显式接收 `agentId`。
3. 作息缓存键从 date 改成 `(agentId, date)`。
4. Agent 切换时取消旧调度、加载目标 Agent 作息、重建目标活动锚点。
5. 运行目标测试和编译。

### Task 7：改造关系、情绪和里程碑

**Files:**
- Modify: `app/src/main/java/com/agent/ta/data/local/dao/RelationshipStateDao.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/dao/EmotionalStateDao.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/dao/MilestoneEventDao.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/RelationshipService.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/RelationshipInitializer.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/EmotionalService.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/RelationshipEngine.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/EmotionalEngine.kt`
- Test: `app/src/androidTest/java/com/agent/ta/domain/AgentScopedRelationshipEmotionTest.kt`

**Steps:**
1. 写测试验证 Agent A 的亲密度、情绪势能变化不影响 B。
2. 服务层构造或方法参数必须绑定 `agentId`，禁止内部临时读取 active agent。
3. 新 Agent 初始化独立关系和情绪默认状态。
4. 运行目标测试、现有 `RelationshipServiceTest`、`EmotionalServiceTest` 和编译。

### Task 8：改造承诺、定时任务和观察者

**Files:**
- Modify: `app/src/main/java/com/agent/ta/data/local/dao/CommitmentDao.kt`
- Modify: `app/src/main/java/com/agent/ta/service/CommitmentScheduler.kt`
- Modify: `app/src/main/java/com/agent/ta/service/CommitmentTriggerReceiver.kt`
- Modify: `app/src/main/java/com/agent/ta/infrastructure/observer/CommitmentObserver.kt`
- Modify: `app/src/main/java/com/agent/ta/infrastructure/observer/RecentConversationObserver.kt`
- Modify: `app/src/main/java/com/agent/ta/infrastructure/observer/ActivityAnchorObserver.kt`
- Modify: `app/src/main/java/com/agent/ta/infrastructure/observer/ObserverRegistry.kt`
- Modify: `app/src/main/java/com/agent/ta/ui/screens/profile/CommitmentScreen.kt`
- Test: `app/src/androidTest/java/com/agent/ta/service/AgentScopedCommitmentTest.kt`

**Steps:**
1. 写测试验证 A/B 的到期任务互不混淆。
2. `PendingIntent` extras 和 requestCode 同时编码 `agentId`、`commitmentId`。
3. Receiver 使用任务自己的 `agentId` 处理状态和写消息，不查询 active agent 替代。
4. 非当前 Agent 的定时任务仍写入对应会话并发送包含 Agent 名称的通知。
5. 定时任务管理页只展示当前 Agent 的任务。
6. 运行目标测试和编译。

---

## Batch 3：Agent 导入、切换和 UI 数据源

### Task 9：把导入改成新实例事务

**Files:**
- Modify: `app/src/main/java/com/agent/ta/domain/AgentImportManager.kt`
- Modify: `app/src/main/java/com/agent/ta/util/AgentConfigImporter.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/dao/AgentConfigDao.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/AgentConfigEditor.kt`
- Modify: `app/src/main/java/com/agent/ta/service/AgentEngine.kt`
- Test: `app/src/androidTest/java/com/agent/ta/domain/AgentImportIsolationTest.kt`

**Steps:**
1. 写测试：同一 ZIP 连续导入两次得到不同 ID、独立空数据空间和 `NOT_STARTED` 状态。
2. 导入时明确执行 `nicknameForUser = ""`，不继承导出者称呼。
3. 数据库事务内插入配置、初始化首次见面/关系/情绪状态并激活新实例。
4. 事务提交后刷新 Provider、重建作息、调度和活动锚点。
5. 扩展 `AgentConfigEditor.updateAgent(agentId, transform)`，异步更新不再依赖 active 记录。
6. 运行目标测试和编译。

### Task 10：聊天 UI 与 Agent 切换使用独立数据源

**Files:**
- Modify: `app/src/main/java/com/agent/ta/ui/screens/chat/ChatScreen.kt`
- Modify: `app/src/main/java/com/agent/ta/ui/screens/agent/AgentConfigScreen.kt`
- Modify: `app/src/main/java/com/agent/ta/ui/navigation/TaNavHost.kt`
- Modify: `app/src/main/java/com/agent/ta/service/AgentForegroundService.kt`
- Modify: `app/src/main/java/com/agent/ta/service/NotificationHelper.kt`
- Test: `app/src/androidTest/java/com/agent/ta/ui/AgentSwitchChatTest.kt`

**Steps:**
1. 写 UI/DAO 集成测试：切换 Agent 后聊天列表立即切换，旧消息不可见，切回后恢复。
2. ChatScreen 订阅 `activeAgentId.flatMapLatest { chatDao.observeAll(it) }`。
3. 导入成功切换到新 Agent 的空会话，并显示正常“正在输入中”。
4. 通知标题按消息所属 Agent 读取名称，不使用当前 active Agent 覆盖。
5. 运行目标测试和编译。

---

## Batch 4：首次见面状态机

### Task 11：实现 FirstMeetingCoordinator 的纯状态机

**Files:**
- Create: `app/src/main/java/com/agent/ta/domain/firstmeeting/FirstMeetingPhase.kt`
- Create: `app/src/main/java/com/agent/ta/domain/firstmeeting/FirstMeetingCoordinator.kt`
- Test: `app/src/test/java/com/agent/ta/domain/firstmeeting/FirstMeetingCoordinatorTest.kt`

**Required phases:**
```text
NOT_STARTED
GREETING_IN_PROGRESS
WAITING_NICKNAME
FOLLOW_UP_ASKED
COMPLETED_WITH_NICKNAME
COMPLETED_WITHOUT_NICKNAME
```

**Steps:**
1. 写纯单元测试覆盖全部合法流转和非法重复触发。
2. `beginGreeting(agentId)` 先以条件更新抢占 `NOT_STARTED → GREETING_IN_PROGRESS`，防止并发生成两次。
3. 问候成功入库后保存 `greetingMessageId` 并进入 `WAITING_NICKNAME`。
4. LLM 失败恢复为 `NOT_STARTED`；TTS 失败不回退，因为文字已成功。
5. 第一次未识别称呼进入 `FOLLOW_UP_ASKED`；第二次仍未识别进入 `COMPLETED_WITHOUT_NICKNAME`。
6. 用户明确拒绝立即结束，不继续追问。
7. 运行单元测试。

### Task 12：新增首次见面对话场景与动态问候

**Files:**
- Modify: `app/src/main/java/com/agent/ta/domain/PromptBuilder.kt`
- Modify: `app/src/main/java/com/agent/ta/data/remote/dto/TtsDto.kt`
- Modify: `app/src/main/java/com/agent/ta/data/remote/LlmClient.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/ChatInteractor.kt`
- Modify: `app/src/main/java/com/agent/ta/service/AgentEngine.kt`
- Test: `app/src/test/java/com/agent/ta/domain/FirstMeetingPromptTest.kt`

**Required output additions:**
```kotlin
data class FirstMeetingMeta(
    val introducedSelf: Boolean = false,
    val askedForNickname: Boolean = false
)
```

**Steps:**
1. 写 Prompt 测试：场景包含第一次见面、主动发起、不得引用历史、必须自我介绍和询问称呼、2～3 条短消息。
2. 为 `ChatInteractor` 增加 `ConversationScene.FIRST_MEETING_GREETING` 和 `FIRST_MEETING_REPLY`。
3. 首次问候只读取当前 Agent 的配置，不读取任何旧 Agent 数据。
4. 校验 `introducedSelf` 和 `askedForNickname`；失败时纠正重试一次，再失败使用最小兜底问句。
5. 使用业务键 `first_meeting:{agentId}:greeting` 或状态表消息 ID 实现持久化幂等。
6. 默认 Agent 模型可用后触发；导入 Agent 事务完成后立即触发。
7. 如果用户先发消息，合并为 `FIRST_MEETING_REPLY`，不再补发突兀主动问候。
8. 运行测试和编译。

---

## Batch 5：称呼提取、校验和配置写入

### Task 13：实现 NicknameValidator 纯本地校验

**Files:**
- Create: `app/src/main/java/com/agent/ta/domain/firstmeeting/NicknameValidator.kt`
- Test: `app/src/test/java/com/agent/ta/domain/firstmeeting/NicknameValidatorTest.kt`

**Required behavior:**
1. 去掉首尾空格、成对引号和“叫我/称呼我为/就行”等残留。
2. 仅允许 1～12 个可见字符。
3. 拒绝换行、URL、JSON、代码片段、纯标点、纯 emoji、完整长句。
4. 拒绝“随便”“都行”“你猜”“不知道”“以后再说”等无意义值。
5. 支持明确清空称呼的独立结果，不把空字符串当校验失败。
6. 用参数化测试覆盖有效、无效和边界输入。

### Task 14：实现结构化 NicknameResolver

**Files:**
- Create: `app/src/main/java/com/agent/ta/domain/firstmeeting/NicknameResolver.kt`
- Modify: `app/src/main/java/com/agent/ta/data/remote/dto/TtsDto.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/PromptBuilder.kt`
- Modify: `app/src/main/java/com/agent/ta/data/remote/LlmClient.kt`
- Test: `app/src/test/java/com/agent/ta/domain/firstmeeting/NicknameResolutionTest.kt`

**Required contract:**
```kotlin
data class NicknameResolution(
    val intent: String = "NONE",
    val nickname: String? = null,
    val confidence: Float = 0f,
    val evidence: String = "",
    val shouldSave: Boolean = false
)
```

**Intent values:** `EXPLICIT_NICKNAME`、`SELF_INTRODUCTION`、`DECLINED`、`AMBIGUOUS`、`CORRECTION`、`CLEAR`、`NONE`。

**Steps:**
1. 写解析测试覆盖缺字段、未知 intent、越界 confidence 和恶意格式。
2. 在同一次普通回复 LLM 请求中输出 `nicknameResolution`，避免额外调用导致回复与提取不一致。
3. 首次见面时基于本轮连续用户消息整体判断。
4. 只有 intent 为明确设置/纠正、`shouldSave=true`、`confidence>=0.85` 且本地校验通过时允许保存。
5. `SELF_INTRODUCTION` 不直接保存，Agent 自然确认“以后叫你 X 可以吗”。
6. 运行测试和编译。

### Task 15：把称呼写入与首次见面推进接入 ChatInteractor

**Files:**
- Modify: `app/src/main/java/com/agent/ta/domain/ChatInteractor.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/AgentConfigEditor.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/firstmeeting/FirstMeetingCoordinator.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/PromptBuilder.kt`
- Test: `app/src/androidTest/java/com/agent/ta/domain/FirstMeetingNicknameIntegrationTest.kt`

**Steps:**
1. 写集成测试：“叫我阿哲”只更新请求所属 Agent，状态完成。
2. 写测试：“我叫张明，你叫我明哥”保存“明哥”；“我叫张明”不保存并追问。
3. 写测试：第一次模糊回答追问一次，第二次仍模糊后结束且不再追问。
4. 写测试：“别叫我宝宝了，叫我阿哲”在首次见面结束后仍更新当前 Agent。
5. 写测试：“直接叫你就行”清空当前 Agent 称呼。
6. LLM 请求开始时捕获 `agentId`，配置、消息、记忆和状态更新全部使用该 ID。
7. 不显示“配置保存成功”系统提示，回复保持人格化自然表达。
8. 运行集成测试和编译。

---

## Batch 6：旧 Onboarding 清理与完整验证

### Task 16：拆除旧 Onboarding 的首次认识职责

**Files:**
- Modify: `app/src/main/java/com/agent/ta/domain/OnboardingManager.kt`
- Modify: `app/src/main/java/com/agent/ta/service/AgentEngine.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/ChatInteractor.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/PromptBuilder.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/entity/OnboardingStateEntity.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/dao/OnboardingStateDao.kt`

**Steps:**
1. 搜索并列出旧 `isOnboarding`、`triggerOnboardingMessage`、`onUserRepliedForOnboarding` 调用。
2. 将人物自我介绍和称呼询问全部迁移到 FirstMeetingCoordinator。
3. 若四步 Onboarding 没有其他实际产品职责，则删除其运行入口；若仍用于权限/功能教学，则改名并与 Agent 实例首次见面完全解耦。
4. 确保升级旧用户不会重新问候。
5. 运行编译和相关测试。

### Task 17：并发、进程恢复和后台触发回归

**Files:**
- Test: `app/src/androidTest/java/com/agent/ta/domain/FirstMeetingRecoveryTest.kt`
- Test: `app/src/androidTest/java/com/agent/ta/domain/AgentSwitchConcurrencyTest.kt`
- Test: `app/src/androidTest/java/com/agent/ta/service/BackgroundAgentIsolationTest.kt`

**Scenarios:**
1. 问候生成中进程重启，状态可恢复且不会重复两条。
2. 问候 LLM 失败后下次进入聊天页重试。
3. TTS 失败仍保留文字问候并进入等待称呼状态。
4. 回复生成期间切换 Agent，结果写回原 Agent，不污染当前会话。
5. 连续导入两个同名 Agent，各自问候一次。
6. 非当前 Agent 的定时任务按时写入自己的会话。
7. Agent A/B 的聊天、记忆、关系、情绪、作息、承诺互不可见。
8. 运行 `./gradlew testDebugUnitTest connectedDebugAndroidTest --no-daemon`。

### Task 18：最终静态验证与 Debug 打包

**Files:**
- No source changes unless verification finds defects.

**Steps:**
1. 运行 `./gradlew testDebugUnitTest --no-daemon`，预期全部通过。
2. 在有设备/模拟器时运行 `./gradlew connectedDebugAndroidTest --no-daemon`，预期全部通过；无设备时明确记录未执行原因。
3. 运行 `./gradlew lintDebug --no-daemon`，修复本次改动引入的错误。
4. 运行 `./gradlew compileDebugKotlin --no-daemon`，预期 `BUILD SUCCESSFUL`。
5. 运行 `./gradlew assembleDebug --no-daemon`，预期生成 `app/build/outputs/apk/debug/app-debug.apk`。
6. 人工验收：首次启动、导入、切换、称呼提取、一次追问、后续修改、定时任务和进程重启。
7. 不自动提交 Git；仅在用户明确要求后再按逻辑批次提交。

---

## Acceptance Criteria

1. 默认 Agent 第一次可用时按人格主动自我介绍并询问称呼，且只发生一次。
2. 每次导入都创建新实例，清空导入包中的用户称呼，并立即触发独立首次问候。
3. 不同 Agent 的聊天、记忆、关系、情绪、作息、承诺和称呼完全隔离。
4. 明确称呼经 LLM 结构化提取和本地校验后，只写入请求所属 Agent。
5. 第一次没有明确回答时自然追问一次，第二次仍不明确则停止打扰。
6. 首次见面结束后仍支持自然修改或清除称呼。
7. LLM/TTS 失败、进程重启、重复触发和切换 Agent 均不会产生重复问候或跨 Agent 写入。
8. v14 旧数据升级到 v15 不丢失，并确定性归属当前 Agent。
9. Debug 编译、单元测试、可执行的仪器测试、lint 和打包均通过。