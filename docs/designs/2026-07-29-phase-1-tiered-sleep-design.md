---
design_type: phase
created_at: 2026-07-29
parent_initiative: companion-agent-vision.md
---

# Phase 1：分级睡眠 + 惊醒机制

## Intent Contract

**目标**：把 UNAVAILABLE 二元睡眠拆分为深睡/浅睡，引入"惊醒"机制，让深夜被消息打扰时有真实人化反应。

**约束**：
- 不破坏现有跨午夜时段处理逻辑（已修复的 TodayScheduleScreen 等保持工作）
- 不重写 PromptBuilder Zone A/B/C 三段架构（在 Zone C 注入睡眠状态场景分支）
- 不替换 AlarmManager 调度（在现有调度上加 LIGHT_SLEEP 状态）
- 数据库 schema 不破坏性变更（DailySlot.sleepDepth 是 JSON 字段，slotsJson 序列化不触发 Room 迁移；AgentConfig.behavior.wakeChancePerDeepSleepMessage 需要兼容默认值）

**成功标准**：
1. Agent 睡觉时段拆分为 3 段（入睡浅睡 / 深睡 / 将醒浅睡），sleepDepth 字段正确填充；切分基于 90 分钟睡眠周期建模 + LLM 输出情境扰动参数（如"今晚压力大，深睡 -10%"），代码合并计算最终时间点
2. 浅睡时段收到消息 → 切换 LIGHT_SLEEP → 短回复（5-15 字）+ 迷糊语气
3. 深睡时段收到消息 → 随机概率触发清醒 → 切换 LIGHT_SLEEP 回复
4. 深睡连续未触发 → 消息标记 pending → 状态切换到将醒浅睡时回复
5. ScheduleAdjuster 拒绝 REPLACE 睡觉时段（PROTECTED_KEYWORDS 已含"睡觉"，需扩展检查 sleepDepth 非空时段）
6. LifeEventInitiator 起床节点判定兼容 LIGHT_SLEEP（不能从 LIGHT_SLEEP 切到 normal 也算起床，要识别为"已醒"）

**风险等级**：medium
- 涉及状态机核心改动（新增 LIGHT_SLEEP 枚举），下游所有状态相关代码（reply delay 配置、UI 显示、transition log）需适配
- LLM 拆分比例可能不合理，需要后处理校验

## Verification Contract

**验证步骤**：

1. **单元测试：SleepPhaseSplitter**
   - 输入：入睡时间 23:00、起床时间 07:30（8.5h=5.67 周期）、无扰动
   - 期望：拆为 3 段，入睡浅睡 ~1.7h、深睡 ~4.7h、将醒浅睡 ~2.1h，时间点对齐到分钟
   - 输入：熬夜 02:00→07:30（5.5h=3.67 周期）、压力大扰动（深睡 -10%）
   - 期望：深睡比例降低，入睡浅睡占比上升
   - 输入：短睡 23:00→05:00（6h=4 周期）、无扰动
   - 期望：第 1 周期为入睡浅睡、中间 2 周期为深睡、最后 1 周期为将醒浅睡（REM 补偿）

2. **状态机集成测试：StateMachine.canReplyNow()**
   - LIGHT_SLEEP 状态 → canReplyNow() 返回 true
   - UNAVAILABLE（深睡）状态 → canReplyNow() 返回 false
   - LIGHT_SLEEP → NORMAL 切换不触发 LifeEventInitiator.WAKE_UP（已醒）

3. **惊醒触发测试：ChatInteractor 深睡分支**
   - 深睡状态收到 1 条消息 → 50% 概率进入 LIGHT_SLEEP（mock 随机值）、50% 保持 UNAVAILABLE 标记 pending
   - LIGHT_SLEEP 回复完成后 → LLM 输出 scheduleAdjustment 决定回深睡或保持浅睡

4. **回归测试：跨午夜时段处理**
   - TodayScheduleScreen 渲染 3 段睡觉 slot，未到的时段不标记为已结束（沿用已修复的跨午夜判断）
   - ScheduleAdjuster 拒绝 REPLACE 含 sleepDepth 的 slot

5. **构建验证**：
   - `./gradlew assembleDebug` 编译通过
   - `./gradlew test` 单元测试通过

**确认信号**：所有测试通过 + 无新增编译告警

## Governance Contract

**Approval Gates**（人工审查节点）：

1. **SleepPhaseSplitter 算法实现完成**（审查睡眠周期建模 + 情境扰动合并逻辑，确保时间计算对齐到分钟、跨午夜处理正确）
2. **AgentState.LIGHT_SLEEP 新增**（审查状态枚举变更、displayName、id 字段，确保下游 AgentState.fromId/getReplyDelaySec/typingIndicatorDuration 配置兼容）
3. **ChatInteractor 深睡惊醒分支**（审查随机概率触发逻辑、LIGHT_SLEEP 切换、回复完成后 LLM 自决回深睡或保持浅睡的状态流，确保不会无限循环惊醒）
4. **PromptBuilder Zone C 睡眠场景分支**（审查迷糊/起床气/刚惊醒的 prompt 注入，确保语气约束符合 persona，不出现"我睡着了"这种出戏表达）
5. **ScheduleAdjuster.sleepDepth 保护扩展**（审查含 sleepDepth 的 slot 不可被 REPLACE 的逻辑，确保 LLM 不能通过工具调用改写睡眠时段活动）

**Rollback**（回滚方案）：

- **代码层**：所有改动集中在新文件 `SleepPhaseSplitter.kt` + 现有文件的增量修改（DailySlot 加字段、StateMachine 加状态、ChatInteractor 加分支、PromptBuilder 加场景分支、ScheduleAdjuster 扩展保护）。回滚时 git revert Phase 1 commit 即可，无 schema 迁移，slotsJson 兼容（sleepDepth 默认 null，旧 slot 仍能解析）
- **数据层**：DailySlot.sleepDepth 是可选字段（`String? = null`），旧作息记录无此字段时仍可解析；AgentConfig.behavior.wakeChancePerDeepSleepMessage 默认 0.15，旧 Agent 配置不破坏
- **运行时降级**：SleepPhaseSplitter 失败时 fallback 到单段 unavailable 跨午夜睡觉（沿用当前 fallbackSchedule 行为）

**Ownership**：Agent（设计 + 实施 + 验证）

**风险升级条件**：
- 若 LIGHT_SLEEP 状态引入导致状态切换图出现循环（如 LIGHT_SLEEP ↔ UNAVAILABLE 反复横跳）→ 暂停实施，回退到设计阶段重新设计状态流
- 若 LLM 情境扰动参数输出不稳定（如给出深睡 -50% 导致拆分崩坏）→ 加校验边界（扰动范围 ±15%）+ fallback 到无扰动

## Scope

| 范围 | 内容 |
|------|------|
| **In（Phase 1 必做）** | DailySlot 新增 `sleepDepth: String?` 字段（"light"/"deep"，仅 unavailable 时段）<br>新增 `SleepPhaseSplitter`：90 分钟周期建模 + LLM 情境扰动参数合并，输出 3 段 sleepDepth 标记的 slots<br>DailyPlanner prompt 扩展：LLM 输出睡觉总 slot + 情境扰动参数（stress/mood/fatigue 影响 ±15%）<br>DailyPlanner.normalizeSlots 兼容 3 段睡眠拆分（不再强制合并为单段）<br>AgentState 新增 `LIGHT_SLEEP` 枚举（id="light_sleep", displayName="浅睡"）<br>StateMachine.canReplyNow() 支持 LIGHT_SLEEP 返回 true<br>StateMachine.computeCurrentSlot() 识别 sleepDepth="light" 的 slot 对应 LIGHT_SLEEP 状态<br>ChatInteractor 深睡收到消息：随机概率（wakeChancePerDeepSleepMessage 默认 0.15）触发切换 LIGHT_SLEEP 回复；未触发则标记 pending<br>ChatInteractor 浅睡收到消息：直接切换 LIGHT_SLEEP 回复<br>ChatInteractor LIGHT_SLEEP 回复完成后：LLM 输出 scheduleAdjustment 自决回深睡（SHIFT 睡眠时段）或保持浅睡<br>PromptBuilder Zone C 新增"睡眠场景"分支：迷糊语气/起床气/刚惊醒的回复约束<br>AgentConfig.behavior 新增 `wakeChancePerDeepSleepMessage: Float = 0.15f`<br>ScheduleAdjuster.isProtectedSlot 扩展：含 sleepDepth 的 slot 不可 REPLACE/SKIP<br>LifeEventInitiator.identifyNodeType 兼容 LIGHT_SLEEP：LIGHT_SLEEP → 非 LIGHT_SLEEP 不触发 WAKE_UP（视为已醒）<br>TodayScheduleScreen 渲染 3 段睡眠 slot（沿用已修复跨午夜逻辑，显示深度标签） |
| **Out（Phase 1 不做）** | Phase 2 关系系统（RelationshipState）<br>Phase 3 情感势能驱动主动发起（EmotionalState）<br>Phase 4 App 内一键克隆<br>午睡的分级拆分（本 phase 只处理夜间睡眠，午睡仍为单段 unavailable）<br>多 Agent 共享睡眠状态（单 Agent 单用户架构不变）<br>TTS 音色按睡眠状态切换（如刚醒沙哑音色，留给后续 voice_director 扩展）<br>睡眠质量记忆持久化（如"昨晚没睡好"影响今天心情，留给 Phase 3 EmotionalState）<br>用户主动叫醒 Agent 的 UI 按钮（只通过消息触发惊醒） |

## Decisions

| # | 决策点 | 选择 | 拒绝的替代方案 | 理由 |
|---|--------|------|---------------|------|
| 1 | 睡眠时段拆分结构 | 3 段独立 slot，中间深睡跨午夜 | 4 段全不跨午夜 / 单段+子结构 | 契合生理学（90 分钟周期）、StateMachine 跨午夜逻辑不变、slots 数量只 +2 |
| 2 | sleepDepth 拆分生成方 | 混合：LLM 输出总 slot + 情境扰动参数，代码按周期建模切分 | LLM 自算时段 / 纯代码模板 | LLM 控制总体比例（情境化），代码保证时间连续性和确定性 |
| 3 | 拆分算法 | 90 分钟睡眠周期建模 + 情境扰动（±15%） | 固定比例 20/55/25 | 短睡眠深睡补偿、长睡眠 REM 增加，符合真实生理学；情境扰动让每天都不一样 |
| 4 | 状态机改造 | 新增 AgentState.LIGHT_SLEEP | 不新增状态 / 新增但不切换 | 状态语义清晰（深睡不可回复/浅睡可回复），typing delay 可按状态档位配置（契合用户偏好档位选择） |
| 5 | 浅睡回复后状态流 | LLM 自决（scheduleAdjustment SHIFT 睡眠时段回深睡，或保持浅睡） | 继续浅睡 / 回深睡 | 赋予 Agent 主动性，符合"像虚拟世界朋友"产品定位；用户 memory 偏好 LLM 主导 |
| 6 | 惊醒关键词机制 | 取消硬编码关键词，改用随机概率 + LLM 清醒判定 | 硬编码关键词 / AgentConfig 可配关键词 | 用户反馈关键词机制不合适，应 Agent 主导；随机概率模拟"被吵醒概率"，LLM 决定回复策略 |
| 7 | 深睡惊醒触发 | 每条消息随机概率（wakeChancePerDeepSleepMessage=0.15）判定是否清醒 | 连 3 条才判定 / 定时清醒 | 每条消息都有机会触发，不可预测性强；成本低（只触发概率命中时才调 LLM） |
| 8 | sleepDepth 字段类型 | `String?`（可选，"light"/"deep"，null 表示非睡眠时段） | 枚举类型 / 嵌套对象 | JSON 序列化兼容旧 slots（null 默认值），不触发 Room 迁移；解析简单 |
| 9 | 情境扰动参数 | LLM 输出 stress(0-1)/fatigue(0-1)/mood(-1~1) 三维，合并影响 ±15% | 单一 stress 参数 / LLM 直接输出权重 | 三维覆盖压力/疲劳/心情，更细腻；±15% 边界防崩坏 |
| 10 | 回深睡机制 | LLM 输出 scheduleAdjustment SHIFT 将当前 LIGHT_SLEEP slot 缩短，后续 deep slot 提前 | 硬编码定时切换 / LLM REPLACE 状态 | 复用现有 ScheduleAdjuster 事件机制，不新增事件类型；SHIFT 保持时段连续性 |
| 11 | ScheduleAdjuster 保护扩展 | isProtectedSlot 检查 `slot.sleepDepth != null` 不可 REPLACE/SKIP | 只检查 activity 关键词 | sleepDepth 非空即睡眠时段，比关键词匹配更可靠（避免"小憩"等遗漏） |
| 12 | LifeEventInitiator 兼容 | LIGHT_SLEEP → 非 LIGHT_SLEEP 不触发 WAKE_UP；UNAVAILABLE(deep) → LIGHT_SLEEP 不触发 SLEEP（已睡着） | 重写 identifyNodeType 逻辑 | 增量判定，最小改动；"已醒"和"已睡着"的中间状态不重复触发节点 |

## Surface

### 新增文件

**`SleepPhaseSplitter.kt`**（domain 层）
- 输入：入睡时间 `LocalTime`、起床时间 `LocalTime`、情境扰动参数 `SleepContextPerturbation(stress, fatigue, mood)`
- 输出：3 段 `DailySlot`（已填充 sleepDepth="light"/"deep"，时间点对齐到分钟）
- 算法：90 分钟周期数 = floor(总时长分钟 / 90)；第 1 周期=入睡浅睡、中间周期=深睡、最后 1-2 周期=将醒浅睡；情境扰动按 stress↑→深睡↓、fatigue↑→入睡浅睡↓、mood<-0.3→深睡↓ 调整权重 ±15%；fallback：拆分失败返回单段 unavailable

### 存储变更

**DailySlot 数据模型**（`AgentConfig.kt`）—— 无 Room 迁移
- 新增 `sleepDepth: String? = null`（"light"/"deep"/null）
- slotsJson 序列化兼容：旧 slots 无此字段，反序列化时默认 null

**AgentConfig.behavior 配置**（`AgentConfig.kt`）
- 新增 `wakeChancePerDeepSleepMessage: Float = 0.15f`
- AgentConfigImporter 默认值兜底，旧 agent.json 无此字段不报错

### APIs / 组件修改

**`DailyPlanner.kt`**（domain）
- `buildPlanPrompt`：prompt 扩展规则——LLM 输出睡觉总 slot（跨午夜）+ `sleepContextPerturbation` 对象（stress/fatigue/mood 0-1）
- `normalizeSlots`：检测最后一个 unavailable slot，调用 `SleepPhaseSplitter.split()` 替换为 3 段；拆分失败 fallback 保留原单段
- `fallbackSchedule`：保持单段 unavailable（LLM 失败时的兜底，不调用 Splitter）

**`StateMachine.kt`**（service）
- `computeCurrentState`：当前 slot.sleepDepth=="light" → 返回 LIGHT_SLEEP；sleepDepth=="deep" → 返回 UNAVAILABLE；sleepDepth==null → 沿用原逻辑
- `canReplyNow`：LIGHT_SLEEP → true；UNAVAILABLE → false
- `getReplyDelaySec`：LIGHT_SLEEP → (30..60) 秒（迷糊状态慢回复）
- `getNextSwitchTime` / `getUpcomingSwitches`：基于 slots 列表遍历，sleepDepth 不影响切换点计算（slot.start/end 已由 Splitter 算好）

**`AgentState.kt`**（data/model）
- 新增枚举 `LIGHT_SLEEP(id="light_sleep", displayName="浅睡")`

**`ChatInteractor.kt`**（domain）
- `sendUserMessage` 第 4 步状态判定：
  - LIGHT_SLEEP → 走延迟回复（30-60s）+ 标记 received
  - UNAVAILABLE + sleepDepth=="deep" → 随机概率判定：命中切换 LIGHT_SLEEP 走回复；未命中标记 pending
  - UNAVAILABLE + sleepDepth=="light"（理论上不该出现，slot 应已被 StateMachine 识别为 LIGHT_SLEEP）→ 降级走浅睡回复
- 回复完成后：LLM 输出 scheduleAdjustment.shouldAdjust=true + SHIFT 类型时，调用 ScheduleAdjuster 调整睡眠时段（如把当前 light slot 缩短、后续 deep slot 提前）

**`PromptBuilder.kt`**（domain，Zone C 扩展）
- 新增"睡眠场景"分支（在场景判定前插入）：
  - state == LIGHT_SLEEP → 注入"你刚被消息吵醒，迷糊状态，回复要短（5-15 字）、语气含糊、可能带起床气、不要长篇大论"
  - state == UNAVAILABLE 且是深睡惊醒后的回复 → 注入"你从深睡中被吵醒，反应迟钝，可能不知道现在几点，回复很短"
- Zone A 当前活动锚点：LIGHT_SLEEP 时显示"状态：浅睡中（被消息吵醒）"

**`ScheduleAdjuster.kt`**（domain）
- `isProtectedSlot`：新增 `slot.sleepDepth != null` → 不可 REPLACE/SKIP
- SHIFT 操作对睡眠时段的特殊处理：SHIFT 整个睡眠时段块（3 段一起顺延），不打乱内部切分

**`LifeEventInitiator.kt`**（service）
- `identifyNodeType`：
  - prevSlot.state=="light_sleep" || (prevSlot.state=="unavailable" && prevSlot.sleepDepth=="light") → newSlot 非 LIGHT_SLEEP → 不触发 WAKE_UP（视为已醒）
  - prevSlot.state=="unavailable" && prevSlot.sleepDepth=="deep" → newSlot.state=="light_sleep" → 不触发 SLEEP（已睡着）

**`TodayScheduleScreen.kt`**（ui）
- 渲染 3 段睡眠 slot：每段显示深度标签（"入睡浅睡"/"深睡"/"将醒浅睡"），沿用已修复的跨午夜判断逻辑

### 触达文件清单

| 层 | 文件 | 改动类型 |
|---|---|---|
| domain | `SleepPhaseSplitter.kt`（新） | 新增 |
| data/model | `AgentConfig.kt` | 加字段（DailySlot.sleepDepth + BehaviorConfig.wakeChancePerDeepSleepMessage） |
| data/model | `AgentState.kt`（若独立文件） | 加枚举 |
| domain | `DailyPlanner.kt` | prompt 扩展 + normalizeSlots 调用 Splitter |
| service | `StateMachine.kt` | 状态判定 + canReplyNow + getReplyDelaySec |
| domain | `ChatInteractor.kt` | 深睡惊醒分支 + 浅睡回复分支 |
| domain | `PromptBuilder.kt` | Zone C 睡眠场景分支 |
| domain | `ScheduleAdjuster.kt` | isProtectedSlot 扩展 + SHIFT 睡眠块特殊处理 |
| service | `LifeEventInitiator.kt` | identifyNodeType 兼容 LIGHT_SLEEP |
| ui | `TodayScheduleScreen.kt` | 渲染深度标签 |

## Risks & Open Questions

### 风险

| # | 风险 | 影响 | 概率 | 缓解措施 |
|---|------|------|------|---------|
| 1 | **LIGHT_SLEEP 状态引入导致状态切换图循环**（LIGHT_SLEEP ↔ UNAVAILABLE 反复横跳，Agent 被一条消息吵醒→回复→回深睡→下一条消息又吵醒） | 状态机抖动、LLM 调用爆炸、通知刷屏 | 中 | 回复完成后 LLM 自决回深睡时，记录 `lastWakeTime`，若距上次惊醒 < 10 分钟则强制保持 LIGHT_SLEEP 不再触发惊醒判定（10 分钟冷却） |
| 2 | **SleepPhaseSplitter 拆分时间点不合理**（如深睡段 < 2 小时、或入睡浅睡 > 1 小时） | 睡眠模式不真实、深睡段过短导致惊醒概率失真 | 中 | 加校验：深睡段 ≥ 2 小时、入睡浅睡 ≤ 1.5 小时、将醒浅睡 ≤ 2 小时；校验失败 fallback 到固定比例 20/55/25 无扰动 |
| 3 | **LLM 情境扰动参数输出不稳定**（如 stress=0.9 导致深睡 -15% 后崩坏，或 LLM 不返回该字段） | 拆分算法异常 / 拆分失败 | 中 | 扰动范围钳制 ±15%；LLM 未返回 sleepContextPerturbation 时默认 (0.3, 0.3, 0.0) 中性值 |
| 4 | **深睡惊醒随机概率触发过频或过疏** | wakeChance=0.15 每条消息 15% 概率，连发 5 条有 56% 概率醒一次——可能太频繁；调低又可能整夜不醒 | 中 | 参数放 BehaviorConfig 可调；默认 0.15 观察一周后根据使用反馈调整；连续惊醒冷却（风险 1 的 10 分钟冷却）兜底 |
| 5 | **ScheduleAdjuster.SHIFT 睡眠块边界 case**（如 SHIFT 把睡眠时段后移导致起床时间晚于次日首个 slot 开始） | 时段不连续、StateMachine.computeCurrentSlot 找不到当前 slot | 低 | SHIFT 后调用 normalizeSlots 重新校验时段连续性；起床时间晚于次日首个 slot 时，压缩首个非睡眠 slot 或提前起床 |
| 6 | **LifeEventInitiator 节点识别漏判**（如 LIGHT_SLEEP → NORMAL 被误判为 WAKE_UP 触发主动消息，导致半夜给用户发消息） | 凌晨静音时段被破坏、用户体验差 | 低 | LIGHT_SLEEP → 任何状态都不触发 WAKE_UP（视为已醒）；静音时段 23:00-08:00 仍生效作为兜底 |
| 7 | **PromptBuilder 睡眠场景 prompt 注入后 LLM 出戏**（如 LLM 写"我正在睡觉被你吵醒"这种打破第四面墙的描述） | Agent 不像真人 | 中 | prompt 明确约束："不要说'我在睡觉被吵醒'，要直接用迷糊的语气自然回复，如'嗯...'、'怎么了...'、'几点了...'"；一致性校验器扩展检查此类出戏表达 |
| 8 | **跨午夜睡眠时段被拆分为 3 段后，TodayScheduleScreen 的 isPast 判断**（已修复的跨午夜 bug）回归 | 未到的睡眠段被标记已结束 | 低 | 沿用已修复逻辑：跨午夜时段 isPast 永远为 false；3 段中只有中间深睡段跨午夜，前后两段不跨午夜，判断逻辑无变化 |

### 开放问题（待 Phase 1 实施中决策）

1. **LIGHT_SLEEP 状态的 typing_indicator_duration 默认值**
   - 候选：30-60 秒（迷糊慢回复）/ 15-30 秒（兼顾响应感）
   - 倾向：30-60 秒，符合"刚醒迷糊"的真实感；用户偏好档位选择，可在 AgentConfig.behavior.typingIndicatorDuration 配置覆盖

2. **惊醒冷却期间的消息处理**
   - 10 分钟冷却内深睡收到新消息：标记 pending 不触发惊醒判定？还是仍走概率判定但降低概率？
   - 倾向：冷却期内直接标记 pending，不触发判定（节省 LLM 调用，且"刚被吵醒又睡着"时不容易再醒）

3. **将醒浅睡时段 Agent 是否可主动发起消息**
   - 将醒浅睡（如 06:00-07:30）时 Agent 已半醒，是否允许 LifeEventInitiator 触发"早起问候"？
   - 倾向：暂不允许，保持静音时段 23:00-08:00 兜底；将醒浅睡收到消息可回复但非主动发起。Phase 3 情感势能驱动时再考虑

4. **情境扰动参数的语义对齐**
   - LLM 输出 stress=0.8 时，代码如何理解？是否需要在 prompt 中给出锚点（如 0.5=普通、0.8=压力很大）？
   - 倾向：prompt 中给锚点示例（0.0=轻松 / 0.5=普通 / 1.0=极度压力），让 LLM 输出有参照
