# Persona Engine 运行时人格系统 设计文档

> **日期**：2026-08-10
> **状态**：已确认（用户选择了「完整系统」+「机制+自动守卫」；Guard 降权方式 B；Context Analyzer 用内置词典；按 1→2→3→4→5 顺序交付）

## 背景与问题

砂金（崩坏星穹铁道角色）人设中"嗜赌/赌局/筹码/下注"等词被密集注入 Prompt，导致 LLM 句句扯到"去赌场赌博"，而角色本义是"商业投资层面的风险偏好人格"。核心问题不是模型理解，而是：

1. **数据层**：人设把性格标签（嗜赌）字面化，且未澄清"赌"的确切含义。
2. **机制层**：`interests` 的"对话中可以自然引入"是显式授权主动带话题；缺少"主题克制"通用规则。
3. **放大层**：对话历史自我强化，赌场话题滚雪球。

**本次目标**：不做针对性文案修补，而是建立**纯通用的运行时人格系统（Persona Engine）**，让任何角色都不会因人设某一主题词偏重而过度聚焦演绎。

## 总体架构

```
                    ┌──────────────────┐
                    │   Persona DB    │  ← 模块1：Trait 数据模型（从现有配置派生）
                    └────────┬─────────┘
                             ▼
用户消息 ───────► Context Analyzer       ← 模块2：分析话题/情绪/意图/风险度
                             ▼
                    ┌──────────────────┐
                    │ Persona Activator│  ← 模块3：决定激活哪些特征、抑制哪些
                    └────────┬─────────┘
                             ▼
                    ┌──────────────────┐
                    │ Expression Budget│  ← 模块4：每类表达允许的强度/频率上限
                    └────────┬─────────┘
                             ▼
                    ┌──────────────────┐
                    │ Prompt Compiler  │  ← 模块4：把激活结果编译进 PromptBuilder
                    └────────┬─────────┘
                             ▼
                          LLM
                             ▼
                    ┌──────────────────┐
                    │ Persona Guard    │  ← 模块5：代码层后置检查（Overexpression）
                    └────────┬─────────┘
                             ▼
                         最终回复
```

## 模块设计

### 模块 1：Persona Trait 数据模型（Persona DB）

**文件**：`domain/persona/PersonaTrait.kt`

不新增用户编辑字段，从现有 `Persona`/`AgentIdentity` 配置**自动派生** trait，向下兼容（无新配字段时回退到当前行为）。

```kotlin
data class PersonaTrait(
    val name: String,              // 如 "risk_seeking"
    val label: String,             // 如 "高风险偏好"
    val priority: Int,             // 0-10
    val activationTopics: List<String>,   // 触发话题（选择/风险/机会/未知/困境/竞争）
    val activationSituations: List<String>, // 触发情境（做决定/遇不确定/失败/高风险）
    val expression: List<String>,  // 表现方式（主动承担风险/从容/鼓励尝试…）
    val lexicalMarkers: List<String>, // 标志性词汇（赌局/筹码/下注…）— optional
    val markerMaxFrequency: Int    // 该类标志词每轮最多出现次数，默认 1
)

data class PersonaModel(
    val traits: List<PersonaTrait>,
    val lexicalMarkers: List<String>,  // 汇总所有 marker（用于 Guard）
    val defaultExpressionLevel: Int    // 默认表现等级，L1
)

object PersonaModelBuilder {
    /** 从 AgentConfig 派生 PersonaModel（纯函数，可测） */
    fun build(config: AgentConfig): PersonaModel
}
```

派生规则（内置中文词典辅助）：
- 从 `identity.personalityCore` / `persona.personality` 提取性格标签 → 映射为 trait（如"嗜赌/冒险/敢于下注"→ `risk_seeking`）。
- 从 `persona.interests` / `persona.catchphrases` 提取标志性词汇 → `lexicalMarkers`。
- 内置一张**性格标签→trait 映射表**（如 冒险/赌博/风险 → risk_seeking；谨慎/保守 → risk_averse；温柔/关怀 → warm；幽默 → playful 等）。未命中标签落到默认 `neutral` trait。

### 模块 2：Context Analyzer（纯函数，内置中文词典）

**文件**：`domain/persona/ContextAnalyzer.kt`

轻量、不调 LLM、可测。

```kotlin
data class ContextAnalysis(
    val topic: String?,      // career_decision / food / weather / casual...
    val emotion: String,     // certain / uncertain / happy / sad / neutral
    val intent: String,      // advice_seeking / casual / question / complaint...
    val riskLevel: Float     // 0-1
)

object ContextAnalyzer {
    private val TOPIC_KEYWORDS: Map<String, List<String>> = mapOf(
        "career_decision" to listOf("辞职", "跳槽", "工作", "公司", "创业", "换工作"),
        "risk_uncertainty" to listOf("会不会", "怎么办", "风险", "不确定", "犹豫", "选择", "要不要"),
        "food" to listOf("吃", "饭", "饿", "外卖", "菜"),
        "weather" to listOf("天气", "下雨", "太阳", "冷", "热"),
        // ...
    )
    private val RISK_KEYWORDS: List<String> = listOf("风险", "赌", "冒险", "搏一把", "不确定", "选择")
    private val UNCERTAIN_KEYWORDS: List<String> = listOf("犹豫", "不知道", "要不要", "怎么办", "纠结")

    fun analyze(text: String): ContextAnalysis
}
```

规则：
- `topic`：按关键词命中得分取最高者；无命中 → `casual`。
- `riskLevel`：`RISK_KEYWORDS` 命中的比例映射到 0-1。
- `emotion`：不确定词命中 → `uncertain`；好事词 → `happy`；负面词 → `sad`；否则 `neutral`。
- `intent`：含"怎么办/要不要/该不该" → `advice_seeking`；含"？"且短 → `question`；含抱怨词 → `complaint`；否则 `casual`。

### 模块 3：Persona Activator（纯函数）

**文件**：`domain/persona/PersonaActivator.kt`

```kotlin
data class ActivationResult(
    val activatedTraits: List<PersonaTrait>,   // 本轮激活的特征
    val suppressedTraits: List<PersonaTrait>,  // 本轮抑制的特征
    val markerBudgetMultipliers: Map<String, Float>, // marker 词 → 预算倍率
    val expressionLevel: Int                   // 本轮表现等级 L0-L3
)

object PersonaActivator {
    fun activate(model: PersonaModel, analysis: ContextAnalysis, recentMarkerFreq: Map<String, Int>): ActivationResult
}
```

规则：
- 若 `analysis.riskLevel >= 0.5` 或 topic 命中 trait 的 `activationTopics` → 该 trait **activate**，`expressionLevel` 提升。
- 与本轮话题无关的 trait（尤其含大量 lexicalMarkers 的）→ **suppress**。
- `markerBudgetMultipliers`：风险话题时相关 marker 预算升高（如 0 → 1 次），普通闲聊时压低（0 次）。
- `recentMarkerFreq`：最近 N 轮某 marker 已出现较多 → 本轮预算进一步压低（防滚雪球）。

### 模块 4：Expression Budget + Prompt 编译（改 PromptBuilder）

**文件**：`domain/PromptBuilder.kt`（核心改动）

1. 新增通用规则块 `buildPersonaActivationRules(sb, activationResult)`，注入 **Zone C**（recency）：
   - 本轮激活哪些特征（如"从容、风险偏好、善于分析"）。
   - 本轮抑制哪些特征（如"赌博兴趣、公司背景、口头禅"）。
   - 标志性词汇"最多出现 0~1 次"（按预算）。
   - 人格表现分级 L0-L3 说明（默认 L1，仅当话题高度相关才到 L3）。
2. **弱化 interests 注入**（通用，已确认）：
   ```kotlin
   sb.appendLine("你感兴趣的话题（用于点缀话题多样性，不要强行引入，用户话题无关时不要扯到）：${...}")
   ```
3. `PromptBuilder.build()` 增加参数 `contextAnalysis: ContextAnalysis? = null` 和 `personaModel: PersonaModel? = null`，内部调 `PersonaActivator.activate` 得到 `ActivationResult` 后注入 Zone C。默认 null 时回退到当前行为（零侵入）。

### 模块 5：Persona Guard（代码层后置守卫）

**文件**：`domain/persona/PersonaGuard.kt`

纯函数做检测，检测不合格时**由调用方（ChatInteractor）触发重新生成**（决策点 B：FLAG 后重新调 LLM 生成一次，带"减少标志性词汇"指令）。

```kotlin
data class GuardCheckResult(
    val isFlagged: Boolean,
    val reason: String?,          // 如 "marker密度超标: 赌x3"
    val flaggedItems: List<Int>   // 命中 items 的下标
)

object PersonaGuard {
    /**
     * 检测回复是否过度表达角色标志性特征
     * @param model PersonaModel
     * @param items 待入库的 reply items
     * @param recentMessages 最近几轮对话（交叉检测重复）
     * @return 是否 FLAG
     */
    fun check(
        model: PersonaModel,
        items: List<ReplyItem>,
        recentMarkerFreq: Map<String, Int>
    ): GuardCheckResult
}
```

检测维度（非简单关键词计数）：
- **marker 密度**：单条 reply 内 marker 词出现次数 > `markerMaxFrequency` → FLAG。
- **语义机械度**：判断 marker 是否被"强贴"（如"今天吃什么？这可是一次赌局"），用"marker 前后是否紧邻高频机械连接词"近似判断；若 marker 出现在合理语境（如"人生本来就是一场赌局"）则不算。
- **跨轮重复**：`recentMarkerFreq` 中某 marker 已出现 ≥ 阈值 → 本轮该 marker 再出现即 FLAG。

**挂载点**：[ChatInteractor.kt](file:///e:/my_projects/AndroidStudioProjects/ta/app/src/main/java/com/agent/ta/domain/ChatInteractor.kt#L971-L979) 的 `cleanedItems` 生成之后、入库之前。

**降权方式（决策 B）**：Guard FLAG 后，调用方重新调 LLM 生成一次，追加指令（如"你上一轮过度使用标志性表达，请保留性格语气，但减少博弈/赌场类比喻，让表达更自然"）。重试也 FLAG 则放弃降权，直接入库当前结果（避免死循环，最多重试 1 次）。

## 数据流（一轮完整对话）

```
用户消息
  → ContextAnalyzer.analyze(text) → ContextAnalysis
  → PersonaModelBuilder.build(config) → PersonaModel（可缓存，配置变更时重建）
  → PersonaActivator.activate(model, analysis, recentMarkerFreq) → ActivationResult
  → PromptBuilder.build(..., contextAnalysis, personaModel) 注入 Zone C
  → LLM 生成 → AgentReply
  → ChatInteractor 组装 items（含括号动作清洗）
  → PersonaGuard.check(model, items, recentMarkerFreq)
       ├─ PASS → 入库
       └─ FLAG → 重新调 LLM 生成（带降权指令）→ 再 guard（最多重试 1 次）→ 入库
```

## 测试策略（TDD）

每个模块独立单元测试（纯函数，无 Android 依赖，放 `test` 目录）：

- **模块1** `PersonaModelBuilderTest`：砂金 config → 派生含 `risk_seeking` trait + `[赌局,筹码,下注]` markers；普通 config → 回退默认。
- **模块2** `ContextAnalyzerTest`："我准备辞职创业，但风险挺大" → topic=career_decision, riskLevel 高, intent=advice_seeking；"今天天气不错" → topic=weather, riskLevel 低。
- **模块3** `PersonaActivatorTest`：风险话题 → risk_seeking 激活、marker 预算升高；闲聊 → marker 抑制。
- **模块4** `PromptBuilderPersonaTest`：注入 activationRegion 后 Zone C 含激活/抑制/预算指令；interests 文案已弱化。
- **模块5** `PersonaGuardTest`：单条回复"赌局×3" → FLAG；"人生本来就是一场赌局"（合理语境）→ PASS；跨轮重复 → FLAG。

## 交付顺序

1 → 2 → 3 → 4 → 5，每个模块独立测试 + 提交，最后在砂金角色上整体验证。

## 不做的事（YAGNI）

- 不做 Persona Compiler（用户创建角色时后台编译编译）—— 本期不需要，`PersonaModelBuilder` 已是运行时派生，未来可平滑升级。
- 不改任何现有 agent.json 字段（纯派生，零迁移）。
- Guard 不做"重写"（方案 A）或"删除分句"（方案 C），只做"FLAG 后重新生成"（方案 B）。