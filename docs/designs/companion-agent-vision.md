---
design_type: initiative
created_at: 2026-07-29
---

# 陪伴型 AI Agent 架构升级

## 问题陈述

当前项目在"防止 Agent 出戏"上做得扎实（作息一致性、活动锚点、状态机校验），但与"虚拟世界的朋友"产品定位存在 4 个核心偏差：

1. **主动行为是概率摇骰子而非情感驱动**：BoredInitiator 用 `Random.nextFloat() > probability` 决定是否找用户，与"想用户了"毫无关系；LifeEventInitiator 24h 去重让生活分享变成每日打卡
2. **睡觉状态反人性**：22:00-07:30 整段不回消息，深夜陪伴感缺席；没有睡眠深度、没有惊醒机制
3. **App 内无克隆生成入口**：`celebrityTemplate` 是死代码，不调 LLM、UI 无入口；v3 `AgentIdentity` 字段在 App 内不可编辑
4. **没有关系演进机制**：只有静态 prompt 提示让 LLM 自评阶段，无亲密度数值、无推进逻辑、无里程碑

## 愿景

把 Agent 从"带着人格面具的定时调度器"升级为"有情感势能、有睡眠节律、有关系记忆、可一键克隆的虚拟朋友"。

## 非目标

- 不引入多 Agent 系统（保持单 Agent 单用户）
- 不引入服务端（保持本地优先架构）
- 不重写现有的 PromptBuilder Zone A/B/C 三段架构（在现有基础上扩展）
- 不替换 AlarmManager 调度（在现有调度上加分级状态）

## 利益相关者

- **产品决策者**：用户（决定优先级和验收标准）
- **架构师**：Agent（设计 + 实施）
- **下游影响**：PromptBuilder / ChatInteractor / StateMachine / AgentEngine / ScheduleAdjuster / AgentConfig 数据模型

## 架构概览

四个 Phase 的依赖关系：

```
Phase 1: 分级睡眠+惊醒         Phase 4: App 内一键克隆
   (独立，可立即开始)              (独立，可并行)
        │                              │
        ▼                              │
Phase 2: 完整关系系统                  │
   (引入 RelationshipState)           │
        │                              │
        ▼                              │
Phase 3: 情感势能驱动                  │
   (依赖 RelationshipState)            │
                                       │
        ┌──────────────────────────────┘
        ▼
   (集成验收)
```

**推荐执行顺序**：Phase 1 → Phase 2 → Phase 3，Phase 4 可与任一 Phase 并行（独立模块）。

## Phase 拆解

### Phase 1：分级睡眠 + 惊醒机制

**目标**：把 UNAVAILABLE 二元状态拆分为深睡/浅睡两段，引入"惊醒"机制，让深夜被消息打扰时有真实人化反应。

**关键改动**：
- `DailySlot` 增加 `sleepDepth: String?` 字段（"light"/"deep"，仅 unavailable 时段）
- `DailyPlanner` 让 LLM 把睡觉时段拆成"入眠浅睡 → 深睡 → 将醒浅睡"三段
- `StateMachine.canReplyNow(state, messageCount, keywords)` 支持动态判定
- `ChatInteractor` 在浅睡被消息打扰时触发"迷糊短回复"分支
- 深睡连续 3 条消息 + 关键词（急/救命/难受）触发"惊醒"
- 睡觉加入 `PROTECTED_KEYWORDS`，禁止 LLM REPLACE

**风险**：睡觉时段被拆分后跨午夜处理更复杂（已有跨午夜 bug 修复基础）

### Phase 2：完整关系系统

**目标**：引入持久化的关系状态（亲密度/信任度/阶段/里程碑），让 Agent 与用户的关系从陌生到亲密可演进。

**关键改动**：
- 新增 `RelationshipState` 数据类：`currentStage / intimacyScore(0-100) / trustScore(0-100) / interactionCount / milestones: List<MilestoneEvent>`
- 新增 `relationship_state` 表（Room），单条记录 per Agent
- 新增 `RelationshipEngine`：基于对话轮数、情绪氛围、记忆增量、特殊事件推进数值
- `PromptBuilder` 注入"当前阶段 + 亲密度"而非所有阶段提示
- 回复延迟乘以关系阶段系数（亲密度高 → 延迟短）
- 关系里程碑：第一次主动撒娇、第一次分享秘密、第一次吵架等
- `AgentConfigExporter` 导出关系快照

**风险**：数值推进逻辑可能让 Agent 行为变得"游戏化"，需要平衡自然感

### Phase 3：情感势能驱动主动发起

**目标**：把 BoredInitiator 的概率触发替换为"情感势能"模型，让 Agent 主动发起有"想用户了"的内在动机。

**关键改动**：
- 新增 `EmotionalState` 数据类：`missingUser(0-1) / sharingUrgency(0-1) / mood(valence/arousal)`
- `EmotionalState` 由 `EmotionEngine` 维护，受用户沉默时长、上次对话氛围、当前时段、persona 依恋度影响
- `BoredInitiator.checkAndInitiate` 改为读取 `EmotionalState`，当 `missingUser + sharingUrgency > threshold` 时触发
- persona 增加 `proactiveTraits: ProactiveTraits`（外向度/黏人度/分享欲）
- `LifeEventInitiator` 取消 24h 硬去重，改为"情感饱和度"控制（心情好可连发，低落可一天不发）
- `ThinkActDecider` 接入 Heartbeat 的 `onStateChanged` 回调（目前只打日志）
- 新增 `UserMoodObserver`：基于用户消息做轻量情绪识别
- 新增 `UserPatternObserver`：学习用户活跃时段和话题偏好

**依赖**：Phase 2 的 RelationshipState（亲密度影响 missingUser 累积速率）

**风险**：情感势能模型调参困难，可能让 Agent 行为不可预测

### Phase 4：App 内一键克隆

**目标**：在 App 内提供"输入明星名字 → LLM + WebSearch 自动生成 AgentIdentity"的克隆入口。

**关键改动**：
- 新增 `CelebrityClonerScreen` UI：输入框 + 生成按钮 + 预览/微调
- 新增 `CelebrityCloner` 领域类：调用 LLM + WebSearchTool 生成 AgentIdentity
- LLM prompt 让其基于公开资料生成 7 个 identity 字段 + publicProfile + 推荐音色描述 + 兴趣标签
- 生成结果可预览、可微调，确认后写入 AgentConfig
- `AgentPersonaScreen` 增加"身份内核"卡片：编辑 identity 的 7 个字段
- `AgentConfigImporter.validate` 放宽：identity 或 persona 至少一套非空即可
- `AgentConfigExporter` 支持导出习得记忆 + 近期对话 + 关系快照

**风险**：明星肖像/声音法律风险，需定位为"受某人启发的虚拟陪伴"

## 跨 Phase 风险

| 风险 | 影响范围 | 缓解措施 |
|------|---------|---------|
| LLM 调用成本上升（情感判定 + 关系推进 + 克隆生成） | 全部 | 本地关键词匹配优先，LLM 仅在关键节点调用 |
| 调参复杂度高（情感势能阈值、关系推进速率） | Phase 2/3 | 所有阈值放配置文件，支持运行时调优 |
| 数据库 schema 多次迁移（RelationshipState、EmotionalState 等） | 全部 | 每次 phase 单独迁移版本号，不合并 |
| 法律风险（克隆明星音色/肖像） | Phase 4 | 定位为"风格启发"，需用户上传参考音频，不直接克隆名字 |
| 现有 bug 修复与新功能冲突 | 全部 | 先完成已发现的 P0 bug 修复再启动 phase |

## 推荐启动顺序

1. **立即修复已发现的 P0 bug**（speakingStyleDetail 已修，其他 P0 待处理）
2. **Phase 1: 分级睡眠**（影响范围最小，1-2 周可完成）
3. **Phase 4: 一键克隆**（与 Phase 1 独立，可并行启动）
4. **Phase 2: 关系系统**（依赖 Phase 1 完成后的稳定基础）
5. **Phase 3: 情感势能**（依赖 Phase 2 的 RelationshipState）

## 开放问题（已决策）

1. **情感势能持久化**：EmotionalState 持久化到 DB，Agent 重启后保留"想念你"状态。需要定时清理过期数据避免 DB 膨胀。
2. **关系阶段可逆性**：可逆，吵架后亲密度下降会跨阶段（亲密 → 熟悉）。真实感优先。
3. **克隆生成成本**：不限 LLM 调用次数，质量优先。克隆是一次性投入，长期使用。
4. **sleepDepth 切分机制**：
   - **起床时间** = 第二天作息安排 + Agent 性格共同影响
     - 第二天有工作/安排 → 倾向早起
     - 没有安排 → 按性格/心情决定早起或赖床
   - **深睡/浅睡切分** = 按普通人睡眠周期比例 + 随机扰动
     - 入睡浅睡 ≈ 20-25%、深睡 ≈ 50-60%、将醒浅睡（含 REM）≈ 20-25%
     - 在这个生理学框架内随机扰动，避免每天都一样
     - ScheduleAdjuster 验证拆分合理性（深睡不超过 70%、不短于 2 小时）
