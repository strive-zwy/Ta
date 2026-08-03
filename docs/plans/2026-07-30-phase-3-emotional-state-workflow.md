---
intent: 让 Agent 拥有"未表达情绪积累"的内心世界：Agent 在对话中产生的情绪波动累积成情感势能，势能超过阈值时驱动主动发起；势能低于阈值时即使 idle 超时也不发起。情绪状态影响回复语气。同时兑现 Phase 1 推迟的"睡眠质量→今天心情基线"承诺。
success_criteria:
  - EmotionalState 数据类落地（valence -1~+1 / arousal 0~1 / potentialEnergy 0-100 / lastEmotion / lastUserInteractionAt / lastDecayAt），持久化到 Room 的 emotional_state 表（DB 版本 13→14）
  - EmotionalEngine 对话轮驱动：LLM 自报 emotionIntensity(-2~+2)，valence 缓慢跟随 + arousal 受强度推高 + 势能按强度累加（|intensity|×8）
  - EmotionalEngine 静默积累：每小时按 Agent 当前 valence 系数积累（>0.5 ×1.5 / 0~0.5 ×1.0 / -0.5~0 ×0.5 / <-0.5 且静默>4h ×(-1.0)）
  - EmotionalEngine 每小时衰减：势能 -2，valence 向 0 漂移 0.05，arousal 向 0.3 漂移 0.03
  - EmotionalEngine 睡眠基线：跨天读取昨日 daily_state，sleepDurationMin<360 → valence -0.3, arousal +0.2；fatigue>0.7 → arousal -0.2
  - BoredInitiator 预筛选层追加势能门控：势能<20 拦截 / 20~79 放行 / ≥80 绕过 30 分钟冷却 / 发起后 -30 消耗
  - PromptBuilder Zone B 注入当前情绪状态 + 语气映射提示；Zone C 输出格式追加 emotionIntensity 字段说明
  - AgentReply DTO 新增 emotionIntensity 字段，LlmClient 解析
  - ChatInteractor 回复后调 onTurnCompleted，用户消息时调 onUserMessageReceived 重置静默计时
  - AgentEngine 跨天触发 applySleepBaselineToEmotion；心跳触发静默积累+衰减
risk_level: medium
auto_approve: false
---

## Steps

- [ ] **Step 1: 创建 EmotionalStateEntity 数据模型**
action: 新建 `app/src/main/java/com/agent/ta/data/local/entity/EmotionalStateEntity.kt`，定义 Room entity：
```kotlin
@Entity(tableName = "emotional_state")
data class EmotionalStateEntity(
    @PrimaryKey
    val id: Long = 1,
    val valence: Float,                  // -1.0(苦)~1.0(乐)
    val arousal: Float,                  // 0.0(平静)~1.0(激动)
    val potentialEnergy: Int,            // 0-100
    val lastEmotion: String?,
    val lastUserInteractionAt: Long,
    val lastDecayAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```
字段用中文注释说明取值范围。验证：编译通过。
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 2: 创建 EmotionalStateDao**
action: 新建 `app/src/main/java/com/agent/ta/data/local/dao/EmotionalStateDao.kt`，参照 RelationshipStateDao 模式：
1. `@Query("SELECT * FROM emotional_state WHERE id = 1 LIMIT 1") suspend fun get(): EmotionalStateEntity?`
2. `@Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(state: EmotionalStateEntity)`
3. `@Query("UPDATE emotional_state SET valence = :valence, arousal = :arousal, potentialEnergy = :potentialEnergy, lastEmotion = :lastEmotion, lastUserInteractionAt = :lastUserInteractionAt, lastDecayAt = :lastDecayAt, updatedAt = :updatedAt WHERE id = 1") suspend fun updateState(valence: Float, arousal: Float, potentialEnergy: Int, lastEmotion: String?, lastUserInteractionAt: Long, lastDecayAt: Long, updatedAt: Long)`
4. `@Query("UPDATE emotional_state SET potentialEnergy = :energy, updatedAt = :updatedAt WHERE id = 1") suspend fun updateEnergy(energy: Int, updatedAt: Long)`
5. `@Query("UPDATE emotional_state SET lastUserInteractionAt = :ts, updatedAt = :updatedAt WHERE id = 1") suspend fun updateLastUserInteraction(ts: Long, updatedAt: Long)`
6. `@Query("UPDATE emotional_state SET lastDecayAt = :ts, updatedAt = :updatedAt WHERE id = 1") suspend fun updateDecayTime(ts: Long, updatedAt: Long)`
验证：编译通过。
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 3: 注册 entity 到 TaDatabase + 写迁移 13→14**
action: 在 `app/src/main/java/com/agent/ta/data/local/TaDatabase.kt` 的 entities 数组追加 `EmotionalStateEntity::class`；将 `version = 13` 改为 `version = 14`；在 DAO 列表追加 `abstract fun emotionalStateDao(): EmotionalStateDao`。在 `app/src/main/java/com/agent/ta/TaApplication.kt` 的 migrations 中追加 Migration(13, 14)：
```kotlin
.addMigrations(object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS emotional_state (
            id INTEGER PRIMARY KEY NOT NULL,
            valence REAL NOT NULL,
            arousal REAL NOT NULL,
            potentialEnergy INTEGER NOT NULL,
            lastEmotion TEXT,
            lastUserInteractionAt INTEGER NOT NULL,
            lastDecayAt INTEGER NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL
        )""")
        val now = System.currentTimeMillis()
        db.execSQL("""INSERT INTO emotional_state (id, valence, arousal, potentialEnergy, lastEmotion, lastUserInteractionAt, lastDecayAt, createdAt, updatedAt) VALUES (1, 0.0, 0.3, 0, NULL, $now, $now, $now, $now)""")
    }
})
```
验证：编译通过。
loop: until 编译通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 4: 在 ServiceLocator 注入 EmotionalStateDao**
action: 在 `app/src/main/java/com/agent/ta/di/ServiceLocator.kt` 中（参照 90-94 行 RelationshipStateDao/MilestoneEventDao 的模式）追加：
```kotlin
val emotionalStateDao: EmotionalStateDao
    get() = database.emotionalStateDao()
```
验证：编译通过。
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 5: 创建 EmotionalEngine 核心计算**
action: 新建 `app/src/main/java/com/agent/ta/domain/EmotionalEngine.kt`，实现四个纯函数（不依赖 DAO/ServiceLocator，方便单元测试）：
1. `data class TurnContext(val emotionIntensity: Float, val emotion: String)` 和 `data class EmotionalUpdate(val newValence: Float, newArousal: Float, energyIncrement: Int, newLastEmotion: String)`
2. `fun applyTurnEnd(ctx: TurnContext, current: EmotionalStateEntity): EmotionalUpdate` — newValence = current.valence*0.7 + intensity.coerceIn(-1f,1f)*0.3；newArousal = (current.arousal + |intensity|*0.2).coerceIn(0,1)；energyIncrement = (|intensity|*8).toInt()
3. `data class SilentUpdate(val energyDelta: Int, val isDecay: Boolean)`
4. `fun applySilentAccumulation(current: EmotionalStateEntity, now: Long): SilentUpdate` — 计算 silentHours = (now - lastUserInteractionAt)/3600000；valence>0.5 → +7；0~0.5 → +5；-0.5~0 → +2（注意：源设计是 +5×0.5=+2.5，向下取整为 +2）；<-0.5 且 silentHours>=4 → -5；<-0.5 且 silentHours<4 → +2
5. `data class DecayUpdate(val newEnergy: Int, newValence: Float, newArousal: Float)`
6. `fun applyHourlyDecay(current: EmotionalStateEntity): DecayUpdate` — newEnergy = max(0, current.potentialEnergy-2)；valence 向 0 漂移 0.05；arousal 向 0.3 漂移 0.03
7. `fun applySleepBaseline(yesterdayState: DailyStateEntity?, current: EmotionalStateEntity): EmotionalStateEntity` — yesterdayState==null 返回 current；sleepDurationMin<360 → valence=-0.3, arousal=0.5；fatigue>0.7 → arousal 再 -0.2（保留下限 0.1）；否则 valence=0, arousal=0.3
验证：编译通过。
loop: until 编译通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 6: 创建 EmotionalService 业务编排**
action: 新建 `app/src/main/java/com/agent/ta/domain/EmotionalService.kt`，封装 EmotionalEngine + DAO 调用（参照 RelationshipService 模式）：
1. `suspend fun getCurrentState(): EmotionalStateEntity` — 首次自动初始化（id=1, valence=0, arousal=0.3, potentialEnergy=0, lastEmotion=null, lastUserInteractionAt=now, lastDecayAt=now）
2. `suspend fun onTurnCompleted(emotionIntensity: Float, emotion: String)` — 调 engine.applyTurnEnd，写回 DAO（势能 clamp 0-100）
3. `suspend fun onUserMessageReceived()` — 更新 lastUserInteractionAt = now（重置静默计时）
4. `suspend fun applyHourlyDecayAndAccumulation()` — 先调 engine.applySilentAccumulation 累加势能，再调 engine.applyHourlyDecay 衰减，写回 DAO，更新 lastDecayAt
5. `suspend fun applySleepBaselineIfNeeded()` — 读取昨日 daily_state（DailyStateDao.getByDate(yesterday)），调 engine.applySleepBaseline，写回 DAO
6. `suspend fun consumeEnergy(amount: Int)` — 势能 -= amount，clamp 0-100，写回 DAO
7. `companion object` 内定义 `POTENTIAL_THRESHOLD_LOW = 20`、`POTENTIAL_THRESHOLD_HIGH = 80`、`CONSUME_ON_INITIATE = 30` 供 BoredInitiator 引用
验证：编译通过。
loop: until 编译通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 7: 扩展 AgentReply DTO 支持 emotionIntensity**
action: 在 `app/src/main/java/com/agent/ta/data/remote/dto/TtsDto.kt` 的 `AgentReply` data class 中新增字段 `val emotionIntensity: Float = 0f`（-2.0~+2.0）。在 `app/src/main/java/com/agent/ta/data/remote/LlmClient.kt` 的 `parseReply` 函数中追加对 `emotionIntensity` 字段的解析（参照 459 行 `parseMilestoneDeclared` 的模式新增 `parseEmotionIntensity(obj)`），在 325/336 行的 AgentReply 构造处追加 `emotionIntensity = parseEmotionIntensity(obj)`，并在 358 行 fallback 的 `AgentReply()` 默认值为 0f。验证：编译通过。
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 8: PromptBuilder 注入情绪状态 + 输出格式扩展**
action: 修改 `app/src/main/java/com/agent/ta/domain/PromptBuilder.kt`：1) `build(...)` 函数签名新增参数 `emotionalState: EmotionalStateEntity? = null`（默认 null 避免破坏现有调用）。2) 在 Zone B（关系阶段注入之后）追加：若 emotionalState != null，注入"【当前情绪状态】效价 {valence} 唤醒度 {arousal} 势能 {potentialEnergy}"和 `emotionToHint(valence, arousal)` 返回的语气提示。3) 实现 `private fun emotionToHint(valence: Float, arousal: Float): String`：valence<-0.5 → "语气低沉克制，句子短，少语气词"；valence<0 → "略带低落，正常回复偏闷"；valence>0.5 → "语气轻快上扬，多语气词"；arousal>0.7 → "语速急促，句子碎片化"；arousal<0.2 → "语速缓慢迟滞，停顿多"。注意多个条件可叠加（用换行连接）。4) 在 Zone C 输出格式段（milestoneDeclared 字段说明之后）追加："若本次回复内心有未充分表达的情绪波动，在 emotionIntensity 字段输出强度（-2=强烈负面，-1=轻微低落，0=平静，1=轻微开心，2=强烈兴奋）。0 表示情绪平淡无波动"。验证：编译通过。
loop: until 编译通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 9: ChatInteractor 接入 EmotionalService**
action: 修改 `app/src/main/java/com/agent/ta/domain/ChatInteractor.kt`：1) 类顶部（69 行 relationshipService 附近）新增 `private val emotionalService = EmotionalService()`。2) 在 `sendUserMessage` 入口（用户消息入库前）调用 `emotionalService.onUserMessageReceived()`（重置静默计时）。3) 在 `generateAgentReply` 中（调用 PromptBuilder.build 处）读取 `emotionalService.getCurrentState()` 并传入新参数 emotionalState。4) 在每条 reply 处理完毕后（Phase 2 onTurnCompleted 调用附近）追加：`emotionalService.onTurnCompleted(emotionIntensity = reply.emotionIntensity, emotion = reply.emotion)`。验证：编译通过。
loop: until 编译通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 10: BoredInitiator 预筛选层追加势能门控**
action: 修改 `app/src/main/java/com/agent/ta/service/BoredInitiator.kt` 的 `checkAndInitiate` 方法：在现有预筛选层第 1 步（静音时段检查）之后、第 2 步（冷却检查）之前追加势能门控：
```kotlin
// Phase 3 情感势能门控
val emotionalState = emotionalService.getCurrentState()
val energy = emotionalState.potentialEnergy
if (energy < EmotionalService.POTENTIAL_THRESHOLD_LOW) {
    Log.d(TAG, "势能 $energy < ${EmotionalService.POTENTIAL_THRESHOLD_LOW}，心里没事不想说，跳过")
    return
}
if (energy >= EmotionalService.POTENTIAL_THRESHOLD_HIGH) {
    Log.d(TAG, "势能 $energy >= ${EmotionalService.POTENTIAL_THRESHOLD_HIGH}，绕过冷却")
    // 跳过下面的冷却检查
} else {
    // 原冷却检查逻辑
    val recentCount = chatDao.countOutboundSince(System.currentTimeMillis() - COOLDOWN_MS)
    if (recentCount > 0) {
        Log.d(TAG, "距上次主动发起不足 30 分钟，跳过")
        return
    }
}
```
类顶部新增 `private val emotionalService = EmotionalService()`。在 Act 层成功发起后（agentInitiate 调用之后）追加 `emotionalService.consumeEnergy(EmotionalService.CONSUME_ON_INITIATE)`。验证：编译通过。
loop: until 编译通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 11: AgentEngine 跨天睡眠基线 + 心跳触发积累衰减**
action: 修改 `app/src/main/java/com/agent/ta/service/AgentEngine.kt`：1) 在 `ensureTodayScheduleFresh` 的跨天清理块中（Phase 2 applyDailyDecayIfNeeded 调用附近）追加 `EmotionalService().applySleepBaselineIfNeeded()`。2) 在 Heartbeat 启动回调中（heartbeat.start 块内）追加每整点触发：检测当前分钟==0 时调用 `EmotionalService().applyHourlyDecayAndAccumulation()`（参照现有 heartbeat tick 模式）。注意：心跳每分钟 tick，只在分钟==0 时触发情绪更新避免高频调用。验证：编译通过。
loop: until 编译通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 12: 创建 EmotionalEngine 单元测试**
action: 新建 `app/src/test/java/com/agent/ta/domain/EmotionalEngineTest.kt`，写 10 个测试用例（参照 RelationshipEngineTest 模式，测试方法名用 snake_case 不用反引号中文）：
1. `applyTurnEnd_positive_intensity_increases_valence` — intensity=2.0, current.valence=0 → newValence > 0
2. `applyTurnEnd_negative_intensity_decreases_valence` — intensity=-2.0, current.valence=0 → newValence < 0
3. `applyTurnEnd_high_arousal_clamped_to_1` — intensity=2.0, current.arousal=0.9 → newArousal = 1.0
4. `applyTurnEnd_energy_increment_proportional_to_intensity` — intensity=2.0 → energyIncrement = 16
5. `applySilentAccumulation_happy_valence_gets_1_5x_increment` — valence=0.6, silentHours=1 → energyDelta = 7
6. `applySilentAccumulation_sad_valence_silent_over_4h_decreases_energy` — valence=-0.6, silentHours=5 → energyDelta = -5, isDecay=true
7. `applySilentAccumulation_sad_valence_silent_under_4h_still_increases` — valence=-0.6, silentHours=2 → energyDelta = 2, isDecay=false
8. `applyHourlyDecay_reduces_energy_and_drifts_to_neutral` — energy=50, valence=0.5, arousal=0.6 → newEnergy=48, newValence<0.5, newArousal<0.6
9. `applyHourlyDecay_clamps_energy_to_zero` — energy=1 → newEnergy = 0
10. `applySleepBaseline_short_sleep_makes_agent_irritable` — yesterdayState.sleepDurationMin=300 → newValence=-0.3, newArousal=0.5
所有断言精确到 0.01。验证：测试通过。
loop: until 测试通过
max_iterations: 4
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat test --tests "com.agent.ta.domain.EmotionalEngineTest" -q 2>&1 | findstr /C:"PASSED" /C:"FAILED" /C:"error"

- [ ] **Step 13: 创建 EmotionalService 集成测试（androidTest）**
action: 新建 `app/src/androidTest/java/com/agent/ta/domain/EmotionalServiceTest.kt`（参照 RelationshipServiceTest 模式，@RunWith(AndroidJUnit4::class)），setup 中清理 emotional_state 表：
1. `getCurrentState_first_call_initializes_neutral_state` — 首次调用返回 valence=0, arousal=0.3, potentialEnergy=0
2. `onTurnCompleted_updates_valence_and_energy` — 调 onTurnCompleted(intensity=2.0, emotion="happy") 后 getCurrentState 的 valence > 0 且 potentialEnergy > 0
3. `onUserMessageReceived_resets_silent_timer` — 调 onUserMessageReceived 后 lastUserInteractionAt 更新为近期时间
4. `consumeEnergy_reduces_potential_energy` — 先 onTurnCompleted 让势能 > 0，再 consumeEnergy(30) 后势能减少 30
验证：androidTest 编译通过。
loop: until 编译通过
max_iterations: 4
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat :app:assembleDebugAndroidTest -q 2>&1 | findstr /C:"error" /C:"BUILD"

- [ ] **Step 14: 全量编译 + assembleDebug 回归**
action: 跑 `gradlew.bat assembleDebug` 确认整个项目编译 + 打包无错误。检查 LLM/TTS/Room/Compose 无回归（编译期错误）。若失败则修复。验证：BUILD SUCCESSFUL。
loop: until BUILD SUCCESSFUL
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat assembleDebug -q 2>&1 | findstr /C:"BUILD" /C:"error"

- [ ] **Step 15: 跑全部单元测试回归**
action: 跑 `gradlew.bat testDebugUnitTest` 确认所有单元测试通过（含 Phase 1 的 SleepPhaseSplitterTest/StateMachineSleepTest/ScheduleAdjusterSleepTest、Phase 2 的 RelationshipEngineTest/RelationshipStageTest、Phase 3 新增的 EmotionalEngineTest）。验证：BUILD SUCCESSFUL 且无 FAILED。
loop: until BUILD SUCCESSFUL
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat testDebugUnitTest -q 2>&1 | findstr /C:"BUILD" /C:"FAILED" /C:"error"

- [ ] **Step 16: 人工审查 Gate — 数据模型与 DB 迁移**
action: 审查 Step 1-4 产出：EmotionalStateEntity 字段设计是否合理（valence/arousal/potentialEnergy 取值范围）、emotional_state 表结构是否规范、Migration 13→14 是否正确插入初始记录（valence=0, arousal=0.3, potentialEnergy=0）、ServiceLocator 注入是否完整。审查文件：`EmotionalStateEntity.kt` / `EmotionalStateDao.kt` / `TaDatabase.kt` / `TaApplication.kt` / `ServiceLocator.kt`。
loop: false
gate: human

- [ ] **Step 17: 人工审查 Gate — Engine 计算逻辑**
action: 审查 Step 5-6 产出：EmotionalEngine 的四个函数（applyTurnEnd/applySilentAccumulation/applyHourlyDecay/applySleepBaseline）计算是否正确（valence 漂移系数 0.7/0.3、arousal 推高 |intensity|*0.2、势能增量 |intensity|*8、静默积累系数 1.5/1.0/0.5/-1.0、衰减率 -2/0.05/0.03、睡眠基线阈值 360/0.7）、valence<-0.5 减势能的 4 小时窗口是否正确、势能 clamp 0-100、EmotionalService 的 consumeEnergy 和 applyHourlyDecayAndAccumulation 顺序（先积累后衰减还是反之）。审查文件：`EmotionalEngine.kt` / `EmotionalService.kt`。
loop: false
gate: human

- [ ] **Step 18: 人工审查 Gate — 集成与门控**
action: 审查 Step 7-11 产出：AgentReply.emotionIntensity 字段解析、PromptBuilder Zone B 情绪注入是否替换了静态 hints（注意：是新增不是替换，关系阶段注入保留）、Zone C 输出格式说明、ChatInteractor 在正确时机调用 onTurnCompleted 和 onUserMessageReceived、BoredInitiator 势能门控阈值（<20 拦截 / ≥80 绕过冷却 / 发起后 -30）、AgentEngine 跨天睡眠基线和心跳整点触发的位置是否正确（避免高频调用）。审查文件：`TtsDto.kt` / `LlmClient.kt` / `PromptBuilder.kt` / `ChatInteractor.kt` / `BoredInitiator.kt` / `AgentEngine.kt`。
loop: false
gate: human
