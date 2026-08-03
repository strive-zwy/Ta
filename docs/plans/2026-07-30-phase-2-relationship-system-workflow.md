---
intent: 引入持久化关系状态系统（RelationshipState），让 Agent 与用户的关系从陌生到知己可演进，包含 5 阶段、对话轮驱动 + 每日信任衰减、LLM 主动声明 + Engine 检测双轨里程碑、亲密高→延迟短（保留下限）
success_criteria:
  - RelationshipState 数据类落地（currentStage / intimacyScore 0-100 / trustScore 0-100 / interactionCount / milestones），持久化到 Room 的 relationship_state 表（DB 版本 12→13）
  - 5 阶段边界：陌生 0-15 / 初识 16-35 / 熟悉 36-60 / 亲密 61-85 / 知己 86-100，阶段切换时自动触发对应里程碑
  - RelationshipEngine 对话轮驱动 intimacy +0.5~2（按情绪氛围加权），每日 trustScore -0.5 衰减
  - LLM reply 中新增 milestoneDeclared 字段，写入 milestone_events 表；Engine 检测到模式（如深夜倾诉 3 次）自动触发兜底里程碑
  - PromptBuilder 注入"当前阶段 + 亲密度"提示，替换 conversationStageHints 静态字典
  - ChatInteractor 回复延迟乘以关系系数 (1.2 - intimacy/100×0.5)，最终值不低于 1 秒
  - AgentConfigExporter 导出关系快照
risk_level: medium
auto_approve: false
---

## Steps

- [ ] **Step 1: 创建 RelationshipState + MilestoneEvent 数据模型**
action: 新建 `app/src/main/java/com/agent/ta/data/local/entity/RelationshipStateEntity.kt`，定义 Room entity：`id: Long = 1`（单条记录 per Agent）、`currentStage: String`（"stranger"/"acquaintance"/"familiar"/"intimate"/"confidant"）、`intimacyScore: Int`（0-100）、`trustScore: Int`（0-100）、`interactionCount: Int`、`lastInteractionAt: Long`、`lastDecayAt: Long`（上次 trust 衰减日期）、`createdAt: Long`、`updatedAt: Long`。新建 `app/src/main/java/com/agent/ta/data/local/entity/MilestoneEventEntity.kt`，定义：`id: Long`（自增）、`type: String`（如 "first_vulnerability"）、`title: String`（显示名，如"第一次袒露脆弱"）、`triggeredAt: Long`、`triggerSource: String`（"llm_declared" / "engine_detected"）、`contextSnapshot: String`（JSON 快照，记录触发时上下文）。两个 entity 都用 `@Entity(tableName = ...)` 注解。验证：编译通过。
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 2: 创建 RelationshipStateDao + MilestoneEventDao**
action: 新建 `app/src/main/java/com/agent/ta/data/local/dao/RelationshipStateDao.kt`，提供 `get(): RelationshipStateEntity?`（单条记录查询，id=1）、`upsert(state: RelationshipStateEntity)`、`updateScores(intimacy: Int, trust: Int, interactionCount: Int, lastInteractionAt: Long, updatedAt: Long)`、`updateStage(stage: String, updatedAt: Long)`。新建 `app/src/main/java/com/agent/ta/data/local/dao/MilestoneEventDao.kt`，提供 `insert(event: MilestoneEventEntity): Long`、`getAll(): List<MilestoneEventEntity>`、`getByType(type: String): List<MilestoneEventEntity>`（用于去重判断，避免同一里程碑重复触发）、`countByTypeSince(type: String, since: Long): Int`（用于 Engine 检测模式，如"深夜倾诉 3 次"）、`getRecent(limit: Int): List<MilestoneEventEntity>`。验证：编译通过。
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 3: 注册 entity 到 TaDatabase + 写迁移 12→13**
action: 在 `app/src/main/java/com/agent/ta/data/local/TaDatabase.kt` 第 38 行后追加 `RelationshipStateEntity::class, MilestoneEventEntity::class` 到 `entities` 数组；将第 39 行 `version = 12` 改为 `version = 13`；在 DAO 列表（43-52 行）追加 `abstract fun relationshipStateDao(): RelationshipStateDao` 和 `abstract fun milestoneEventDao(): MilestoneEventDao`。在 `app/src/main/java/com/agent/ta/TaApplication.kt` 第 156 行后追加 Migration 12→13：创建 `relationship_state` 表（id INTEGER PRIMARY KEY, current_stage TEXT, intimacy_score INTEGER, trust_score INTEGER, interaction_count INTEGER, last_interaction_at INTEGER, last_decay_at INTEGER, created_at INTEGER, updated_at INTEGER）和 `milestone_events` 表（id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, title TEXT, triggered_at INTEGER, trigger_source TEXT, context_snapshot TEXT），并插入一条初始 RelationshipState 记录（id=1, current_stage="stranger", intimacy_score=0, trust_score=0, interaction_count=0, created_at=now, updated_at=now）。验证：编译通过 + DB 迁移无报错。
loop: until 编译通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 4: 在 ServiceLocator 注入新 DAO**
action: 在 `app/src/main/java/com/agent/ta/di/ServiceLocator.kt` 中新增 `val relationshipStateDao: RelationshipStateDao` 和 `val milestoneEventDao: MilestoneEventDao`，getter 调用 `database.relationshipStateDao()` 和 `database.milestoneEventDao()`（参照 76-77 行 dailyScheduleDao 的模式）。验证：编译通过。
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 5: 创建 RelationshipStage 枚举**
action: 新建 `app/src/main/java/com/agent/ta/data/model/RelationshipStage.kt`，定义枚举：`STRANGER("stranger", "陌生", 0..15)`、`ACQUAINTANCE("acquaintance", "初识", 16..35)`、`FAMILIAR("familiar", "熟悉", 36..60)`、`INTIMATE("intimate", "亲密", 61..85)`、`CONFIDANT("confidant", "知己", 86..100)`。每个枚举常量包含 `id: String`、`displayName: String`、`scoreRange: IntRange`。提供 `companion object fromScore(score: Int): RelationshipStage` 按 score 落入哪个区间返回对应阶段，`fromId(id: String): RelationshipStage?` 按 id 解析。验证：编译通过。
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 6: 创建 RelationshipEngine 核心推进逻辑**
action: 新建 `app/src/main/java/com/agent/ta/domain/RelationshipEngine.kt`，实现：1) `applyTurnEnd(ctx: TurnContext): RelationshipUpdate` — 接收 `emotion: String`（来自 AgentReply.emotion）、`isUserInitiated: Boolean`、`messageLength: Int`，计算 intimacy 增量：基础 +0.5，按情绪加权（happy/joyful ×1.5、sad/vulnerable ×2.0、angry ×0.3、neutral ×1.0），按消息长度加权（>50 字 ×1.2，>200 字 ×1.5）；trustScore 增量 = intimacy 增量 × 0.6（信任积累比亲密慢）；interactionCount +1。2) `applyDailyDecay(state: RelationshipStateEntity): RelationshipStateEntity` — trustScore 每日 -0.5（按 lastDecayAt 判断是否已执行今日衰减），intimacyScore 每日 -0.2（亲密也微衰减防止停滞）。3) `checkStageTransition(oldScore: Int, newScore: Int): RelationshipStage?` — 检测是否跨越阶段边界，若跨越返回新阶段枚举。4) `shouldTriggerMilestoneByPattern(ctx: TurnContext, recentMilestones: List<MilestoneEventEntity>): String?` — Engine 兜底检测：如深夜（22:00-02:00）倾诉次数 ≥ 3 且无对应里程碑 → 返回 "late_night_confidant"；连续 3 天对话 ≥ 10 轮 → 返回 "consistent_chat"。验证：编译通过。
loop: until 编译通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 7: 创建 RelationshipInitializer（首次启动初始化）**
action: 新建 `app/src/main/java/com/agent/ta/domain/RelationshipInitializer.kt`，实现 `ensureInitialized(): RelationshipStateEntity`：查询 relationshipStateDao.get()，若为 null（首次启动）则插入初始记录（id=1, stage="stranger", scores=0, createdAt=now），返回当前状态。验证：编译通过。
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 8: 创建 RelationshipService 业务编排**
action: 新建 `app/src/main/java/com/agent/ta/domain/RelationshipService.kt`，封装 RelationshipEngine + DAO 调用：1) `onTurnCompleted(emotion: String, isUserInitiated: Boolean, messageLength: Int)` — 调 engine.applyTurnEnd，写回 DAO，若 stage 切换则插入"stage_transition" 里程碑（type="stage_transition_to_${newStage.id}", title="关系进入${newStage.displayName}阶段"）。2) `applyDailyDecayIfNeeded()` — 跨天调用，写回 DAO。3) `recordMilestone(type: String, title: String, source: String, context: Map<String, Any>)` — 写 milestone_events 表，去重检查（同 type 24 小时内不重复，stage_transition 不重复）。4) `getCurrentState(): RelationshipStateEntity` — 读取当前状态（首次自动初始化）。5) `getRecentMilestones(limit: Int = 5): List<MilestoneEventEntity>` — 读取最近里程碑供 prompt 注入。验证：编译通过。
loop: until 编译通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 9: 扩展 AgentReply DTO 支持 milestoneDeclared**
action: 在 `app/src/main/java/com/agent/ta/data/remote/dto/TtsDto.kt` 的 `AgentReply` data class 中新增字段 `val milestoneDeclared: String? = null`（如 "first_vulnerability" / "first_argument" / "first_secret_shared"）。在 `app/src/main/java/com/agent/ta/data/remote/LlmClient.kt` 的 JSON 解析逻辑中追加对 `milestoneDeclared` 字段的解析（参照 commitments 字段解析模式）。验证：编译通过。
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 10: PromptBuilder 注入关系阶段 + 里程碑历史**
action: 修改 `app/src/main/java/com/agent/ta/domain/PromptBuilder.kt`：1) `build(...)` 函数签名（68-97 行）新增参数 `relationshipState: RelationshipStateEntity?` 和 `recentMilestones: List<MilestoneEventEntity>`（默认空列表，避免破坏现有调用）。2) 在 Zone B（245-481 行）的 386-393 行位置（当前 conversationStageHints 注入处），替换为动态注入：注入"【关系当前阶段】${stage.displayName}（亲密度 ${intimacyScore}/100，信任度 ${trustScore}/100）"和对应阶段的 prompt hint（hardcode 在 PromptBuilder 内部或抽取为 stagePromptHints map）。3) 在 Zone B 末尾追加"【近期关系里程碑】"段，列出最近 3 个里程碑的 title 和时间。4) 在 Zone C 输出格式段追加"若本次回复涉及关系节点（首次袒露脆弱/首次吵架/首次分享秘密等），在 milestoneDeclared 字段输出对应 type 字符串；否则该字段留空"。验证：编译通过。
loop: until 编译通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 11: ChatInteractor 接入 RelationshipService**
action: 修改 `app/src/main/java/com/agent/ta/domain/ChatInteractor.kt`：1) 在类顶部（53-68 行附近）新增 `private val relationshipService = RelationshipService()` 和 `private val relationshipEngine = RelationshipEngine()`。2) 在 `generateAgentReply` 函数中（LLM 调用前）调用 `relationshipService.getCurrentState()` 并传入 PromptBuilder.build（参照 Step 10 的新签名）。3) 在回复处理完毕后（每条 reply 处理后），调用 `relationshipService.onTurnCompleted(emotion = reply.emotion, isUserInitiated = true, messageLength = reply.replyText.length)`，同时若 `reply.milestoneDeclared` 非空则调 `relationshipService.recordMilestone(reply.milestoneDeclared, title = milestoneTitleMap[type] ?: type, source = "llm_declared", context = mapOf("replyText" to reply.replyText))`。4) 调用 `relationshipEngine.shouldTriggerMilestoneByPattern(...)`，若返回 type 则调 `recordMilestone(..., source = "engine_detected")`。验证：编译通过。
loop: until 编译通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 12: ChatInteractor 回复延迟加关系系数**
action: 修改 `app/src/main/java/com/agent/ta/domain/ChatInteractor.kt` 的 `resolveTypingDelaySec`（319-327 行）：在函数末尾返回前，读取 `relationshipService.getCurrentState()`，计算 `coefficient = (1.2 - intimacyScore / 100.0 * 0.5)`（即 intimacy=0 时 ×1.2、intimacy=100 时 ×0.7），将原 delay 乘以 coefficient 后 `coerceAtLeast(1L)`（保留下限 1 秒）再返回。注意：连续对话路径（202-204 行的 CONTINUOUS_DELAY_RANGE）不应用系数，保持快速节奏。验证：编译通过。
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 13: AgentEngine 跨天衰减触发**
action: 修改 `app/src/main/java/com/agent/ta/service/AgentEngine.kt` 的 `ensureTodayScheduleFresh` 方法（参照 Phase 1 跨天清理逻辑位置）：在跨天检测成功后（scheduleMutex.withLock 内），调用 `RelationshipService().applyDailyDecayIfNeeded()`，先 ensureInitialized 再执行衰减。验证：编译通过。
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 14: 创建里程碑 type → title 映射表**
action: 在 `app/src/main/java/com/agent/ta/domain/RelationshipService.kt` 内部定义 `MILESTONE_TITLE_MAP: Map<String, String>`，覆盖常见 type：`"first_vulnerability" → "第一次袒露脆弱"`、`"first_argument" → "第一次争吵"`、`"first_secret_shared" → "第一次分享秘密"`、`"first_initiative_care" → "第一次主动关心你"`、`"first_emoji_to_user" → "第一次对你用表情"`、`"late_night_confidant" → "愿深夜相伴"`、`"consistent_chat" → "持续陪伴"`、`"stage_transition_to_acquaintance" → "关系进入初识阶段"`、`"stage_transition_to_familiar" → "关系进入熟悉阶段"`、`"stage_transition_to_intimate" → "关系进入亲密阶段"`、`"stage_transition_to_confidant" → "关系进入知己阶段"`。未匹配的 type 直接用 type 字符串作为 title。验证：编译通过。
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 15: AgentConfigExporter 导出关系快照**
action: 修改 `app/src/main/java/com/agent/ta/domain/AgentConfigExporter.kt`：在 `export(...)` 函数（40-86 行）中，在写入 `agent.json` 后追加写入 `relationship.json`：序列化当前 `RelationshipStateEntity` 和最近 50 条 `MilestoneEventEntity`（按 triggeredAt 倒序）为 JSON 写入 zip。不需要修改 `rewritePathsToRelative`（关系数据无文件路径）。验证：编译通过。
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 16: 创建 RelationshipEngine 单元测试**
action: 新建 `app/src/test/java/com/agent/ta/domain/RelationshipEngineTest.kt`，写 5 个测试用例：1) `applyTurnEnd_neutral_short_message_returns_base_increment` — emotion="neutral", isUserInitiated=true, messageLength=30 → intimacy 增量 = 0.5（基础 ×1.0 ×1.0）。2) `applyTurnEnd_happy_long_message_gets_weighted_increment` — emotion="happy", messageLength=100 → intimacy 增量 = 0.5 × 1.5 × 1.2 = 0.9。3) `applyTurnEnd_angry_reduces_increment` — emotion="angry" → 增量 = 0.5 × 0.3 = 0.15。4) `applyDailyDecay_reduces_trust_and_intimacy` — 初始 trust=50, intimacy=50，衰减后 trust=49.5（向下取整 49 或保留浮点），intimacy=49.8。5) `checkStageTransition_crossing_boundary_returns_new_stage` — oldScore=15, newScore=16 → 返回 ACQUAINTANCE；oldScore=60, newScore=61 → 返回 INTIMATE；oldScore=50, newScore=55 → 返回 null（未跨边界）。所有断言精确到 0.1。验证：测试通过。
loop: until 测试通过
max_iterations: 4
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat test --tests "com.agent.ta.domain.RelationshipEngineTest" -q 2>&1 | findstr /C:"PASSED" /C:"FAILED" /C:"error"

- [ ] **Step 17: 创建 RelationshipStage 枚举测试**
action: 新建 `app/src/test/java/com/agent/ta/data/model/RelationshipStageTest.kt`，写 4 个测试用例：1) `fromScore_zero_returns_stranger` — score=0 → STRANGER。2) `fromScore_fifty_six_returns_familiar` — score=56 → FAMILIAR。3) `fromScore_hundred_returns_confidant` — score=100 → CONFIDANT。4) `fromId_acquaintance_returns_correct_enum` — id="acquaintance" → ACQUAINTANCE。验证：测试通过。
loop: until 测试通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat test --tests "com.agent.ta.data.model.RelationshipStageTest" -q 2>&1 | findstr /C:"PASSED" /C:"FAILED" /C:"error"

- [ ] **Step 18: 创建 RelationshipService 集成测试（androidTest）**
action: 新建 `app/src/androidTest/java/com/agent/ta/domain/RelationshipServiceTest.kt`，写 4 个测试用例（@RunWith(AndroidJUnit4::class)，需 ServiceLocator 初始化）：1) `getCurrentState_first_call_initializes_stranger_state` — 首次调用返回 stage="stranger", scores=0。2) `onTurnCompleted_increments_intimacy_and_trust` — 调用 onTurnCompleted(emotion="happy", isUserInitiated=true, messageLength=80) 后 getCurrentState 的 intimacyScore > 0 且 trustScore > 0。3) `recordMilestone_inserts_to_milestone_events_table` — 调用 recordMilestone("first_vulnerability", "测试里程碑", "llm_declared", emptyMap()) 后 getRecentMilestones(1) 包含该记录。4) `recordMilestone_dedup_skips_same_type_within_24h` — 连续两次调用相同 type 的 recordMilestone，第二次被去重跳过，getRecentMilestones(5) 仍只有 1 条。验证：androidTest 在 emulator 上通过。
loop: until androidTest 通过
max_iterations: 4
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat :app:assembleDebugAndroidTest -q 2>&1 | findstr /C:"error" /C:"BUILD"

- [ ] **Step 19: 全量编译 + assembleDebug 回归**
action: 跑 `gradlew.bat assembleDebug` 确认整个项目编译 + 打包无错误。检查 LLM/TTS/Room/Compose 无回归（编译期错误）。若失败则修复。验证：BUILD SUCCESSFUL。
loop: until BUILD SUCCESSFUL
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat assembleDebug -q 2>&1 | findstr /C:"BUILD" /C:"error"

- [ ] **Step 20: 跑全部单元测试回归**
action: 跑 `gradlew.bat testDebugUnitTest` 确认所有单元测试通过（含 Phase 1 的 SleepPhaseSplitterTest 和 Phase 2 新增的 RelationshipEngineTest + RelationshipStageTest）。验证：BUILD SUCCESSFUL 且无 FAILED。
loop: until BUILD SUCCESSFUL
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat testDebugUnitTest -q 2>&1 | findstr /C:"BUILD" /C:"FAILED" /C:"error"

- [ ] **Step 21: emulator 集成测试 + 视觉验证**
action: 1) `gradlew.bat :app:installDebug` 重装 app。2) `adb shell am instrument -w -e class com.agent.ta.domain.RelationshipServiceTest com.agent.ta.test/androidx.test.runner.AndroidJUnitRunner` 跑 RelationshipServiceTest。3) 启动 app，进入「设置 → 今日作息」相邻的「Agent 配置」或聊天界面，发送若干消息触发 onTurnCompleted，观察 logcat 中 RelationshipService 日志输出（intimacyScore 增长、stage 切换、milestone 触发）。4) 截图验证 UI 无崩溃。验证：androidTest 全通过 + logcat 出现 RelationshipService 日志。
loop: until androidTest 全通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat :app:assembleDebugAndroidTest -q 2>&1 | findstr /C:"BUILD" /C:"error"
gate: human

- [ ] **Step 22: 人工审查 Gate — 数据模型与 DB 迁移**
action: 审查 Step 1-4 产出：RelationshipStateEntity / MilestoneEventEntity 字段设计是否合理、relationship_state + milestone_events 表结构是否规范、Migration 12→13 是否正确插入初始记录、ServiceLocator 注入是否完整。审查文件：`RelationshipStateEntity.kt` / `MilestoneEventEntity.kt` / `RelationshipStateDao.kt` / `MilestoneEventDao.kt` / `TaDatabase.kt` / `TaApplication.kt` / `ServiceLocator.kt`。
loop: false
gate: human

- [ ] **Step 23: 人工审查 Gate — Engine 推进逻辑与衰减策略**
action: 审查 Step 5-8 产出：RelationshipStage 枚举边界是否合理、RelationshipEngine.applyTurnEnd 增量计算是否正确（情绪加权 + 长度加权）、applyDailyDecay 衰减率是否会让数值长期停滞或异常下降、checkStageTransition 边界判定、RelationshipService 的里程碑去重和 stage_transition 自动触发逻辑。审查文件：`RelationshipStage.kt` / `RelationshipEngine.kt` / `RelationshipInitializer.kt` / `RelationshipService.kt`。
loop: false
gate: human

- [ ] **Step 24: 人工审查 Gate — PromptBuilder 与 ChatInteractor 集成**
action: 审查 Step 9-15 产出：AgentReply.milestoneDeclared 字段解析、PromptBuilder 注入关系阶段是否替换了静态 hints、ChatInteractor 在正确时机调用 onTurnCompleted 和 recordMilestone、回复延迟系数公式 (1.2 - intimacy/100×0.5) 和下限保护是否正确、AgentEngine 跨天衰减触发位置、AgentConfigExporter 导出 relationship.json 是否影响 zip 结构。审查文件：`TtsDto.kt` / `LlmClient.kt` / `PromptBuilder.kt` / `ChatInteractor.kt` / `AgentEngine.kt` / `AgentConfigExporter.kt`。
loop: false
gate: human
