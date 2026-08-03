---
intent: 把 UNAVAILABLE 二元睡眠拆分为深睡/浅睡，引入"惊醒"机制，让深夜被消息打扰时有真实人化反应
success_criteria:
  - Agent 睡觉时段拆分为 3 段（入睡浅睡/深睡/将醒浅睡），sleepDepth 字段正确填充，切分基于 90 分钟睡眠周期建模 + LLM 情境扰动参数
  - 浅睡时段收到消息 → 切换 LIGHT_SLEEP → 短回复（5-15 字）+ 迷糊语气
  - 深睡时段收到消息 → 随机概率触发清醒 → 切换 LIGHT_SLEEP 回复
  - 深睡连续未触发 → 消息标记 pending → 状态切换到将醒浅睡时回复
  - ScheduleAdjuster 拒绝 REPLACE 含 sleepDepth 的 slot
  - LifeEventInitiator 起床节点判定兼容 LIGHT_SLEEP
risk_level: medium
auto_approve: false
---

## Steps

- [ ] **Step 1: 创建 SleepPhaseSplitter 数据类与算法骨架**
action: 新建 `app/src/main/java/com/agent/ta/domain/SleepPhaseSplitter.kt`，定义 `SleepContextPerturbation(stress: Float, fatigue: Float, mood: Float)` 数据类（取值范围 stress 0-1、fatigue 0-1、mood -1~1），实现 `split(sleepStart: LocalTime, wakeTime: LocalTime, perturbation: SleepContextPerturbation): List<DailySlot>` 方法骨架。算法逻辑：计算总睡眠分钟数 → 90 分钟周期数 = floor(总分钟/90) → 第 1 周期=入睡浅睡、中间周期=深睡、最后 1-2 周期=将醒浅睡（短睡眠 ≤4 周期时最后 1 周期为将醒浅睡；长睡眠 ≥5 周期时最后 2 周期为将醒浅睡）→ 情境扰动调整权重（stress↑→深睡↓、fatigue↑→入睡浅睡↓、mood<-0.3→深睡↓，范围 ±15%）→ 输出 3 段 DailySlot（已填充 sleepDepth="light"/"deep"），中间深睡段跨午夜。时间点对齐到分钟。fallback：总睡眠 < 3 小时或 > 14 小时直接返回单段 unavailable
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" /C:"SleepPhaseSplitter" || echo COMPILE_OK

- [ ] **Step 2: 实现 SleepPhaseSplitter 拆分核心算法**
action: 在 `SleepPhaseSplitter.kt` 中实现完整算法：1) `computeCycleCount(totalMinutes)` 返回周期数；2) `computePhaseWeights(cycleCount, perturbation)` 返回 triple(入睡浅睡周期数, 深睡周期数, 将醒浅睡周期数)，应用扰动规则（基础权重：1/剩余-1或-2/1或2，扰动后钳制 ±15%）；3) `cyclesToTimeBounds(start, cycles, isCrossMidnight)` 把周期数转换为起止时间字符串 "HH:MM"，跨午夜处理用 modulo 24:00；4) `validateSlots(slots)` 校验深睡段 ≥ 2 小时、入睡浅睡 ≤ 1.5 小时、将醒浅睡 ≤ 2 小时，校验失败返回 null。5) split 方法串起来，validate 失败时 fallback 到固定比例 20/55/25 无扰动重新切分；再失败返回单段 unavailable。所有时间计算用 LocalTime.plusMinutes + modulo 1440 处理跨午夜
loop: until 编译通过且算法骨架完整
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 3: 编写 SleepPhaseSplitter 单元测试**
action: 新建 `app/src/test/java/com/agent/ta/domain/SleepPhaseSplitterTest.kt`，写 4 个测试用例：1) 标准睡眠 23:00-07:30（8.5h=5 周期）+ 无扰动 → 断言 3 段，入睡浅睡 ~1.7h、深睡 ~4.7h（跨午夜）、将醒浅睡 ~2.1h，sleepDepth 字段正确；2) 熬夜 02:00-07:30（5.5h=3 周期）+ stress=0.8 → 深睡比例降低；3) 短睡 23:00-05:00（6h=4 周期）→ 第 1 周期入睡浅睡、中间 2 周期深睡、最后 1 周期将醒浅睡；4) 异常输入 23:00-23:30（< 3 小时）→ fallback 返回单段 unavailable。所有时间点断言精确到分钟
loop: until 测试通过
max_iterations: 4
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat test --tests "com.agent.ta.domain.SleepPhaseSplitterTest" -q 2>&1 | findstr /C:"PASSED" /C:"FAILED" /C:"error"

- [ ] **Step 4: DailySlot 增加 sleepDepth 字段**
action: 在 `app/src/main/java/com/agent/ta/data/model/AgentConfig.kt` 的 `data class DailySlot` 中新增字段 `val sleepDepth: String? = null`（"light"/"deep"/null）。确认 kotlinx.serialization @Serializable 默认值机制让旧 slotsJson 无此字段时反序列化为 null（无需自定义 serializer）。确认无 Room 迁移需求（slotsJson 是 JSON 字符串列存储）
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 5: AgentState 新增 LIGHT_SLEEP 枚举**
action: 先 Grep 找到 `enum class AgentState` 定义位置（在 `AgentConfig.kt` 或独立 `AgentState.kt`）。在枚举中新增 `LIGHT_SLEEP(id="light_sleep", displayName="浅睡")`。确认 AgentState.fromId("light_sleep") 能正确返回。确认 displayName 在 UI 中显示"浅睡"
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 6: BehaviorConfig 增加 wakeChancePerDeepSleepMessage**
action: 在 `AgentConfig.kt` 的 `data class BehaviorConfig` 中新增字段 `val wakeChancePerDeepSleepMessage: Float = 0.15f`。确认 @Serializable 默认值让旧 agent.json 无此字段时不报错
loop: false
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 7: StateMachine 适配 LIGHT_SLEEP 状态**
action: 在 `app/src/main/java/com/agent/ta/service/StateMachine.kt` 修改：1) `computeCurrentState`：读取当前 slot，sleepDepth=="light" → 返回 AgentState.LIGHT_SLEEP；sleepDepth=="deep" → 返回 AgentState.UNAVAILABLE；sleepDepth==null → 沿用原 state 字段逻辑；2) `canReplyNow`：LIGHT_SLEEP → true，UNAVAILABLE → false（沿用）；3) `getReplyDelaySec`：LIGHT_SLEEP 状态时返回 (30..60).random().toLong()（迷糊慢回复），在 null 分支的 when 中加 LIGHT_SLEEP -> (30..60).random().toLong()。不改 getNextSwitchTime / getUpcomingSwitches（slot.start/end 已由 Splitter 算好，遍历逻辑无变化）
loop: until 编译通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 8: DailyPlanner prompt 扩展 + normalizeSlots 调用 Splitter**
action: 在 `app/src/main/java/com/agent/ta/domain/DailyPlanner.kt` 修改：1) `buildPlanPrompt` 在输出格式说明中追加：LLM 在 slots 数组外可输出可选字段 `sleepContextPerturbation: {stress: 0.0-1.0, fatigue: 0.0-1.0, mood: -1.0~1.0}`，锚点示例（0.0=轻松 / 0.5=普通 / 1.0=极度压力；mood -1=低落 / 0=平静 / 1=愉悦）；2) `parseSlotsFromReply` 解析 sleepContextPerturbation 字段（若 LLM 未返回则默认 (0.3, 0.3, 0.0) 中性值）；3) `normalizeSlots` 修改：检测最后一个 unavailable slot（睡觉），如果只有 1 段跨午夜 unavailable，调用 `SleepPhaseSplitter.split(start, end, perturbation)` 替换为 3 段；若已经是 3 段（LLM 直接输出或已拆分过）则跳过；拆分失败（返回 null）保留原单段。fallbackSchedule 保持单段 unavailable 不调用 Splitter
loop: until 编译通过
max_iterations: 4
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 9: ChatInteractor 深睡惊醒 + 浅睡回复分支**
action: 在 `app/src/main/java/com/agent/ta/domain/ChatInteractor.kt` 的 `sendUserMessage` 方法第 4 步状态判定处修改：1) LIGHT_SLEEP → 标记 status="received"，走延迟回复（30-60s 已由 StateMachine.getReplyDelaySec 返回），不 return；2) UNAVAILABLE + 当前 slot.sleepDepth=="deep" → 读取 `configProvider.get().behavior.wakeChancePerDeepSleepMessage`，若 Random.nextFloat() < wakeChance 则调用 `AgentEngine.switchTo(AgentState.LIGHT_SLEEP)` 切换状态后走回复路径（标记 received）；否则标记 status="pending" 并 return@launch；3) UNAVAILABLE + sleepDepth=="light"（理论上不该出现）→ 降级走 LIGHT_SLEEP 回复路径。同时新增 companion field `@Volatile private var lastWakeTime: Long = 0L` 和 `WAKE_COOLDOWN_MS = 10 * 60 * 1000L`，在深睡惊醒判定前检查 `if (System.currentTimeMillis() - lastWakeTime < WAKE_COOLDOWN_MS) { 标记 pending; return@launch }`，惊醒触发时记录 lastWakeTime。回复完成后的 scheduleAdjustment 处理已存在（沿用），LLM 输出 SHIFT 时通过 ScheduleAdjuster 调整睡眠时段
loop: until 编译通过
max_iterations: 4
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 10: PromptBuilder Zone C 睡眠场景分支**
action: 在 `app/src/main/java/com/agent/ta/domain/PromptBuilder.kt` 的 `buildZoneC` 方法中，在现有场景判定（isInitiate / isPendingCatchup / 默认回复）之前插入睡眠场景分支：1) state == AgentState.LIGHT_SLEEP → sb.appendLine 注入"【当前是浅睡惊醒场景】你刚被消息从睡梦中吵醒，迷糊状态。回复约束：长度 5-15 字、语气含糊、可能带起床气、不要长篇大论、不要说'我在睡觉被你吵醒'这种出戏表达，直接用迷糊的语气自然回复如'嗯...'、'怎么了...'、'几点了...'。只发 1 条。"；2) state == AgentState.UNAVAILABLE 且是深睡惊醒后的回复（实际上 LIGHT_SLEEP 切换后 state 已是 LIGHT_SLEEP，此分支用于双保险）→ 注入"你从深睡中被吵醒，反应迟钝，可能不知道现在几点，回复很短"。同时在 Zone A 的活动锚点显示中，LIGHT_SLEEP 时追加"（浅睡中，被消息吵醒）"。在 Zone C 的禁止出戏规则中追加："不要说'我在睡觉'、'我被你吵醒'这种打破第四面墙的描述"
loop: until 编译通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 11: ScheduleAdjuster isProtectedSlot 扩展 + SHIFT 睡眠块处理**
action: 在 `app/src/main/java/com/agent/ta/domain/ScheduleAdjuster.kt` 修改：1) `isProtectedSlot`：在现有 `PROTECTED_KEYWORDS` 和跨午夜判定之外，新增 `if (slot.sleepDepth != null) return true`（含 sleepDepth 即睡眠时段，不可 REPLACE/SKIP）；2) `applyShift` 方法：检测 fromIdx 之后是否含 sleepDepth 非空的时段块（连续 3 段睡眠），若有则 SHIFT 整个睡眠块一起顺延（3 段的 start/end 同步偏移），不打乱内部切分；3) `applyExtend`/`applyShorten`：如果当前 slot 含 sleepDepth，拒绝调整（return 原 slots），避免打乱睡眠结构
loop: until 编译通过
max_iterations: 4
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 12: LifeEventInitiator identifyNodeType 兼容 LIGHT_SLEEP**
action: 在 `app/src/main/java/com/agent/ta/service/LifeEventInitiator.kt` 的 `identifyNodeType` 方法修改：1) 在开头判定 prevWasUnavailable 时追加：`val prevWasLightSleep = prevSlot?.state == "light_sleep" || (prevSlot?.state == "unavailable" && prevSlot?.sleepDepth == "light")`；2) 起床节点判定改为 `if (prevWasUnavailable && !prevWasLightSleep && !newIsUnavailable) return NodeType.WAKE_UP`（prevWasLightSleep 视为已醒，不触发 WAKE_UP）；3) 睡觉节点判定改为 `if (!prevWasUnavailable && !prevWasLightSleep && newIsUnavailable) return NodeType.SLEEP`；4) 新增 `if (prevWasLightSleep && (newState == AgentState.NORMAL || newState == AgentState.IDLE)) return null`（已醒状态切到正常不算生活节点）；5) 深睡切到浅睡（惊醒）不触发任何节点：`if (prevSlot?.state == "unavailable" && prevSlot?.sleepDepth == "deep" && newState == AgentState.LIGHT_SLEEP) return null`
loop: until 编译通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 13: TodayScheduleScreen 渲染睡眠深度标签**
action: 在 `app/src/main/java/com/agent/ta/ui/screens/profile/TodayScheduleScreen.kt` 修改：在渲染 slot 的 Composable 中，如果 `slot.sleepDepth != null` 则在 activity 文本后追加深度标签——sleepDepth=="light" 时根据 slot 位置判定是"入睡浅睡"还是"将醒浅睡"（第一个 light=入睡浅睡，最后一个 light=将醒浅睡，中间的 light 也算入睡/将醒的过渡），sleepDepth=="deep" 显示"深睡"。沿用已修复的跨午夜判断逻辑（跨午夜时段 isPast 永远为 false），3 段中只有中间深睡段跨午夜，前后两段不跨午夜，判断逻辑无变化。UI 显示样式：深度标签用小号灰色文字，不影响整体视觉
loop: until 编译通过
max_iterations: 3
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat compileDebugKotlin -q 2>&1 | findstr /C:"error" || echo COMPILE_OK

- [ ] **Step 14: 编写 StateMachine + ChatInteractor 集成测试**
action: 新建 `app/src/test/java/com/agent/ta/service/StateMachineSleepTest.kt`，写 3 个测试：1) LIGHT_SLEEP 状态 canReplyNow() 返回 true；2) UNAVAILABLE（深睡 slot）状态 canReplyNow() 返回 false；3) 给 StateMachine.init 传入含 sleepDepth 的 slots，computeCurrentState 返回正确状态（light slot → LIGHT_SLEEP、deep slot → UNAVAILABLE）。新建 `app/src/test/java/com/agent/ta/domain/ScheduleAdjusterSleepTest.kt`：1) 构造含 sleepDepth="deep" 的 slot，调用 applyReplace，断言返回原 slots（受保护）；2) 构造 LIGHT_SLEEP → NORMAL 状态切换的 prevSlot/newSlot，调用 LifeEventInitiator.identifyNodeType，断言返回 null（不触发 WAKE_UP）
loop: until 测试通过
max_iterations: 4
verify:
  type: shell
  command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat test --tests "com.agent.ta.service.StateMachineSleepTest" --tests "com.agent.ta.domain.ScheduleAdjusterSleepTest" -q 2>&1 | findstr /C:"PASSED" /C:"FAILED" /C:"error"

- [ ] **Step 15: 全量构建验证 + 回归测试**
action: 运行全量构建 `gradlew.bat assembleDebug` 确认编译通过无新增告警；运行 `gradlew.bat test` 确认所有单元测试通过（含新增的 SleepPhaseSplitterTest、StateMachineSleepTest、ScheduleAdjusterSleepTest 和已有测试）。检查日志输出无 LIGHT_SLEEP 相关异常
loop: until 全部通过
max_iterations: 3
verify:
  - type: shell
    command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat assembleDebug 2>&1 | findstr /C:"BUILD SUCCESSFUL" /C:"BUILD FAILED" /C:"error:"
  - type: shell
    command: cd /d e:\my_projects\AndroidStudioProjects\ta && gradlew.bat test 2>&1 | findstr /C:"PASSED" /C:"FAILED" /C:"Tests"

- [ ] **Step 16: 人工审查 Approval Gate 1 - SleepPhaseSplitter 算法**
action: 提交 SleepPhaseSplitter.kt + SleepPhaseSplitterTest.kt 给用户审查。重点审查：1) 90 分钟周期建模是否正确（周期数计算、阶段划分）；2) 情境扰动合并逻辑（stress/fatigue/mood 影响方向、±15% 钳制）；3) 跨午夜处理（中间深睡段跨午夜、时间对齐到分钟）；4) fallback 逻辑（< 3h 或 > 14h、validate 失败时的降级路径）
loop: false
gate: human
verify:
  type: human-review
  check: SleepPhaseSplitter 算法正确性 + 跨午夜处理 + fallback 逻辑

- [ ] **Step 17: 人工审查 Approval Gate 2 - LIGHT_SLEEP 状态机集成**
action: 提交 AgentState.kt + StateMachine.kt + ChatInteractor.kt 改动给用户审查。重点审查：1) LIGHT_SLEEP 枚举定义；2) computeCurrentState 的 sleepDepth 判定；3) canReplyNow + getReplyDelaySec 适配；4) ChatInteractor 深睡惊醒随机概率触发 + 10 分钟冷却 + 浅睡回复分支；5) 确保不会无限循环惊醒（冷却机制兜底）
loop: false
gate: human
verify:
  type: human-review
  check: LIGHT_SLEEP 状态机集成 + 惊醒冷却 + 无循环风险

- [ ] **Step 18: 人工审查 Approval Gate 3 - PromptBuilder 睡眠场景 + ScheduleAdjuster 保护**
action: 提交 PromptBuilder.kt + ScheduleAdjuster.kt + LifeEventInitiator.kt 改动给用户审查。重点审查：1) Zone C 睡眠场景分支的 prompt 注入（迷糊语气约束、禁止出戏表达）；2) ScheduleAdjuster.isProtectedSlot 的 sleepDepth 扩展 + SHIFT 睡眠块整体顺延；3) LifeEventInitiator 兼容 LIGHT_SLEEP 的节点判定（已醒不触发 WAKE_UP、惊醒不触发 SLEEP）
loop: false
gate: human
verify:
  type: human-review
  check: 睡眠场景 prompt + 保护机制 + 节点判定兼容
