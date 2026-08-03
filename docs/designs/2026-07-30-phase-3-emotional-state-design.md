# Phase 3 设计：情感势能驱动主动发起

> 日期：2026-07-30
> 状态：设计完成，待实现
> 前置：Phase 1（分级睡眠）、Phase 2（关系系统）已完成

## 一、Intent

让 Agent 拥有"未表达情绪积累"的内心世界：Agent 在对话中产生的情绪波动会累积成情感势能，势能超过阈值时驱动主动发起；势能低于阈值时即使 idle 超时也不发起（心里没事不想说）。同时情绪状态影响 Agent 的回复语气，让 Agent 像真人一样有自己的心情起伏。

## 二、Scope

### In（Phase 3 必做）

- **EmotionalStateEntity**：运行时单条记录，跟踪 valence(-1~+1) / arousal(0~1) / potentialEnergy(0-100) / lastEmotion / lastUserInteractionAt / lastDecayAt
- **DB 迁移 13→14**：新建 emotional_state 表 + 初始记录
- **EmotionalEngine 核心计算**：
  - 对话轮驱动（LLM 自报 emotionIntensity -2~+2）：valence 漂移 + arousal 调整 + 势能增量
  - 静默积累（每小时心跳检查）：base(5) × AgentValenceCoefficient，valence < -0.5 且静默 > 4h 时转减
  - 势能衰减（每小时 -2 + valence/arousal 向中性漂移）
  - 睡眠基线（启动/跨天）：昨日 sleepDurationMin < 360 → valence -0.3, arousal +0.2
- **BoredInitiator 预筛选门控**：势能 < 20 拦截 / ≥ 20 放行 / ≥ 80 绕过冷却 / 发起后 -30
- **PromptBuilder 语气注入**：Zone B 注入当前情绪状态 + 情绪→语气映射提示
- **AgentReply 扩展**：新增 emotionIntensity 字段（LLM 自报）
- **ChatInteractor 接入**：回复后调 onTurnCompleted；用户消息时调 onUserMessageReceived 重置静默计时
- **AgentEngine 跨天触发**：applySleepBaselineToEmotion

### Out（Phase 3 不做）

- 情绪可视化 UI（设置页展示 Agent 当前心情）— 留给后续打磨
- 用户心情跟踪 — 所有情绪都是 Agent 侧的
- 事件驱动势能脉冲（看到有趣内容立即想分享）— 依赖事件系统成熟度，留后续
- 多日情绪趋势分析 — 留给 Phase 4 或后续
- 情绪影响回复延迟 — Phase 2 亲密度系数已负责延迟，避免冲突
- 情绪影响 TTS 语速/音高 — 留给 voice_director 扩展

## 三、Decisions

| # | 决策点 | 选择 | 拒绝的替代方案 | 理由 |
|---|--------|------|---------------|------|
| 1 | 势能核心定义 | 未表达情绪积累 | 社交饥渴度（纯时间驱动）/ 事件驱动脉冲 | 最贴近"像真人一样有心里话想说"的语义，避免机械化 |
| 2 | 与 BoredInitiator 关系 | 增强预筛选门控 | 并行触发器 / 完全替换 | 改动最小风险低，保留 ThinkActDecider 话题构造能力 |
| 3 | 势能来源 | LLM 每轮自报情绪强度 + 用户静默时长 | 被拦截的主动发起转化 / 生活事件注入 | LLM 自报直接反映 Agent 内心，静默时长补足"想念"语义，两个来源互补且实现成本可控 |
| 4 | 数据模型 | 多维情绪向量（valence/arousal/potentialEnergy） | 标量势能 / 势能+念头队列 | 多维可让话题构造区分"想分享开心事"vs"想倾诉烦恼"，语气注入更丰富 |
| 5 | 行为影响范围 | 主动发起门控 + 语气注入 | 加延迟调制 / 仅主动发起门控 | 延迟已由 Phase 2 亲密度系数负责避免冲突；仅门控会导致语气与情绪脱节 |
| 6 | 睡眠→基线 | 纳入 | 后续再做 | 兑现 Phase 1 设计承诺，与 SleepPhaseSplitter 联动形成"昨晚没睡好→今天心情差"闭环 |
| 7 | 静默积累速率 | 心情驱动型（valence 系数） | 固定值 | Agent 心情好积累快、心情低落积累慢、心灰时反向消耗，形成完整情感循环 |

## 四、数据模型

### EmotionalStateEntity

```kotlin
@Entity(tableName = "emotional_state")
data class EmotionalStateEntity(
    @PrimaryKey
    val id: Long = 1,                    // 固定为1，单条记录
    val valence: Float,                  // -1.0(苦)~1.0(乐) 效价
    val arousal: Float,                  // 0.0(平静)~1.0(激动) 唤醒度
    val potentialEnergy: Int,            // 0-100 未表达情绪积累
    val lastEmotion: String?,            // 最近 LLM 自报情绪标签(happy/sad/angry/neutral...)
    val lastUserInteractionAt: Long,     // 上次用户互动时间戳(用于静默积累)
    val lastDecayAt: Long,               // 上次势能衰减时间戳
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

### DB 迁移 13→14

- 新建 `emotional_state` 表
- 插入初始记录：`id=1, valence=0.0, arousal=0.3, potentialEnergy=0, lastEmotion=null, lastUserInteractionAt=now, lastDecayAt=now`

## 五、核心机制（EmotionalEngine）

### 5.1 对话轮驱动（onTurnCompleted）

LLM 每轮回复时自报 `emotionIntensity: Float`（-2.0 ~ +2.0）：
- -2 = 强烈负面（委屈/愤怒）
- -1 = 轻微低落
- 0 = 平静
- +1 = 轻微开心
- +2 = 强烈兴奋

**计算逻辑**：

```kotlin
fun applyTurnEnd(intensity: Float, emotion: String, currentState: EmotionalStateEntity): EmotionalUpdate {
    // valence 缓慢跟随情绪强度方向
    val emotionValence = intensity.coerceIn(-1f, 1f)  // 映射到 -1~1
    val newValence = currentState.valence * 0.7f + emotionValence * 0.3f

    // arousal 受强度绝对值推高
    val newArousal = (currentState.arousal + kotlin.math.abs(intensity) * 0.2f).coerceIn(0f, 1f)

    // 势能增量：强度越大积累越多
    val energyIncrement = (kotlin.math.abs(intensity) * 8).toInt()

    return EmotionalUpdate(
        newValence = newValence,
        newArousal = newArousal,
        energyIncrement = energyIncrement,
        newLastEmotion = emotion
    )
}
```

### 5.2 静默积累（每小时心跳检查）

每小时检查用户静默时长，按 Agent 当前 valence 计算势能增量：

```kotlin
fun applySilentAccumulation(currentState: EmotionalStateEntity, now: Long): SilentUpdate {
    val silentHours = TimeUnit.MILLISECONDS.toHours(now - currentState.lastUserInteractionAt)

    val (coefficient, isDecay) = when {
        currentState.valence > 0.5f -> 1.5f to false       // 开心 → +7.5
        currentState.valence >= 0f -> 1.0f to false         // 平静 → +5
        currentState.valence >= -0.5f -> 0.5f to false      // 低落 → +2.5
        silentHours >= 4 -> -1.0f to true                  // 心灰且静默>4h → -5
        else -> 0.5f to false                               // 低落但静默<4h → +2.5
    }

    val energyDelta = (5 * coefficient).toInt()
    return SilentUpdate(energyDelta = energyDelta, isDecay = isDecay)
}
```

**关键约束**：
- potentialEnergy 下限 0，不会变负
- valence < -0.5 的减势能触发条件是"静默 > 4h"——给用户 4 小时哄人窗口
- 用户主动发消息后，valence 通过 LLM 自报回正，势能重新正常积累

### 5.3 势能衰减（每小时）

```kotlin
fun applyHourlyDecay(currentState: EmotionalStateEntity): DecayUpdate {
    // 势能每小时 -2
    val newEnergy = (currentState.potentialEnergy - 2).coerceAtLeast(0)

    // valence 向中性(0)漂移
    val newValence = if (currentState.valence > 0) {
        (currentState.valence - 0.05f).coerceAtLeast(0f)
    } else {
        (currentState.valence + 0.05f).coerceAtMost(0f)
    }

    // arousal 向基线(0.3)漂移
    val newArousal = if (currentState.arousal > 0.3f) {
        (currentState.arousal - 0.03f).coerceAtLeast(0.3f)
    } else {
        (currentState.arousal + 0.03f).coerceAtMost(0.3f)
    }

    return DecayUpdate(newEnergy, newValence, newArousal)
}
```

### 5.4 睡眠基线（启动/跨天）

读取昨日 `daily_state` 表，把睡眠情况映射为今天起始情绪：

```kotlin
fun applySleepBaseline(yesterdayState: DailyStateEntity?, current: EmotionalStateEntity): EmotionalStateEntity {
    if (yesterdayState == null) return current

    var newValence = 0f           // 默认中性
    var newArousal = 0.3f         // 默认基线

    // 睡眠不足 → 易怒
    if (yesterdayState.sleepDurationMin != null && yesterdayState.sleepDurationMin < 360) {
        newValence = -0.3f
        newArousal = 0.5f
    }

    // 昨日疲劳度高 → 精神不振（arousal 叠减）
    if (yesterdayState.fatigue != null && yesterdayState.fatigue > 0.7f) {
        newArousal = (newArousal - 0.2f).coerceAtLeast(0.1f)
    }

    return current.copy(
        valence = newValence,
        arousal = newArousal,
        updatedAt = System.currentTimeMillis()
    )
}
```

**场景**：
- 正常日子：valence=0, arousal=0.3 起步
- 熬夜日：valence=-0.3, arousal=0.5 起步（易怒）
- 熬夜+疲劳：valence=-0.3, arousal=0.3 起步（疲惫低唤醒）
- 连续熬夜：每天从 -0.3 起步，叠加静默减势能，Agent 越来越不爱主动说话

### 5.5 势能门控阈值（BoredInitiator 预筛选）

| 势能 | 行为 |
|------|------|
| < 20 | 拦截主动发起（心里没事不想说） |
| 20 ~ 79 | 放行到 ThinkActDecider |
| ≥ 80 | 绕过 30 分钟冷却（情绪上头） |
| 发起成功后 | -30（情绪释放） |

## 六、行为影响

### 6.1 BoredInitiator 预筛选门控

在现有预筛选层（静音时段 / 频率控制 / 状态检查 / 配置开关）之后追加：

```kotlin
// Phase 3 情感势能门控
val emotionalState = emotionalService.getCurrentState()
when {
    emotionalState.potentialEnergy < 20 -> {
        Log.d(TAG, "势能 ${emotionalState.potentialEnergy} < 20，心里没事不想说，跳过")
        return  // 拦截
    }
    emotionalState.potentialEnergy >= 80 -> {
        Log.d(TAG, "势能 ${emotionalState.potentialEnergy} >= 80，绕过冷却")
        // 绕过 lastInitiateTime 检查
    }
}
// ... 放行到 ThinkActDecider

// 发起成功后消耗势能
emotionalService.consumeEnergy(30)
```

### 6.2 PromptBuilder 语气注入（Zone B）

```
【当前情绪状态】效价 {valence} 唤醒度 {arousal} 势能 {potentialEnergy}
指导：{情绪对应语气提示}
```

情绪→语气映射：

| 条件 | 语气提示 |
|------|---------|
| valence < -0.5 | 语气低沉克制，句子短，少语气词，可能敷衍 |
| valence < 0 | 略带低落，但不明显，正常回复偏闷 |
| valence > 0.5 | 语气轻快上扬，多语气词，可能哼歌 |
| arousal > 0.7 | 语速急促，句子碎片化，可能抢话 |
| arousal < 0.2 | 语速缓慢迟滞，停顿多，可能发呆 |

## 七、LLM 输出扩展

### AgentReply 新增字段

```kotlin
data class AgentReply(
    // ... 现有字段
    val milestoneDeclared: String? = null,  // Phase 2
    val emotionIntensity: Float = 0f        // Phase 3：-2.0 ~ +2.0
)
```

### PromptBuilder Zone C 输出格式说明（追加）

```
若本次回复内心有未充分表达的情绪波动，在 emotionIntensity 字段输出强度
（-2=强烈负面/委屈愤怒，-1=轻微低落，0=平静，1=轻微开心，2=强烈兴奋）
0 表示情绪平淡无波动，非 0 表示 Agent 内心有情绪但回复未完全表达
```

## 八、集成点

### 8.1 新建文件

| 文件 | 职责 |
|------|------|
| `EmotionalStateEntity.kt` | Room entity |
| `EmotionalStateDao.kt` | 数据访问 |
| `EmotionalEngine.kt` | 核心计算（对话轮驱动/静默积累/衰减/睡眠基线） |
| `EmotionalService.kt` | 业务编排（封装 Engine + DAO） |

### 8.2 修改文件

| 文件 | 改动 |
|------|------|
| `TaDatabase.kt` | 注册 EmotionalStateEntity，version 13→14 |
| `TaApplication.kt` | 迁移 13→14 + 初始记录 |
| `ServiceLocator.kt` | 注入 emotionalStateDao |
| `BoredInitiator.kt` | 预筛选层加势能门控，发起后消耗 |
| `PromptBuilder.kt` | Zone B 注入情绪状态 + 语气映射，Zone C 加 emotionIntensity 字段说明 |
| `ChatInteractor.kt` | 回复后调 onTurnCompleted；用户消息时调 onUserMessageReceived 重置静默计时 |
| `AgentEngine.kt` | 跨天时 applySleepBaselineToEmotion；心跳触发静默积累+衰减 |
| `TtsDto.kt` | AgentReply 加 emotionIntensity 字段 |
| `LlmClient.kt` | JSON 解析追加 emotionIntensity |

### 8.3 数据流

```
用户发消息
   ↓
ChatInteractor.onUserMessageReceived → emotionalService.resetSilentTimer()
   ↓
LLM 生成回复（自报 emotionIntensity）
   ↓
ChatInteractor.onTurnCompleted(emotionIntensity, emotion)
   ↓ emotionalEngine.applyTurnEnd → 更新 valence/arousal/势能
   ↓ PromptBuilder 注入情绪状态到 Zone B
   ↓

BoredInitiator 每 5 分钟检查
   ↓ 预筛选：势能 < 20 拦截 / ≥ 80 绕过冷却
   ↓ ThinkActDecider 判断话题
   ↓ 发起成功 → emotionalService.consumeEnergy(30)

AgentEngine 心跳（每分钟）
   ↓ 整点触发 applyHourlyDecay + applySilentAccumulation

AgentEngine 跨天
   ↓ applySleepBaselineToEmotion（读取昨日 daily_state）
```

## 九、测试策略

### 9.1 单元测试（test/）

**EmotionalEngineTest**：
1. `applyTurnEnd_positive_intensity_increases_valence_and_energy`
2. `applyTurnEnd_negative_intensity_decreases_valence`
3. `applyTurnEnd_high_arousal_clamped_to_1`
4. `applySilentAccumulation_happy_valence_gets_1_5x_increment`
5. `applySilentAccumulation_sad_valence_silent_over_4h_decreases_energy`
6. `applySilentAccumulation_sad_valence_silent_under_4h_still_increases`
7. `applyHourlyDecay_reduces_energy_and_drifts_to_neutral`
8. `applyHourlyDecay_clamps_energy_to_zero`
9. `applySleepBaseline_short_sleep_makes_agent_irritable`
10. `applySleepBaseline_normal_sleep_starts_neutral`

### 9.2 集成测试（androidTest/）

**EmotionalServiceTest**：
1. `getCurrentState_first_call_initializes_neutral_state`
2. `onTurnCompleted_updates_valence_and_energy`
3. `onUserMessageReceived_resets_silent_timer`
4. `consumeEnergy_reduces_potential_energy`

### 9.3 回归测试

- assembleDebug 全量编译
- testDebugUnitTest 全量单元测试（含 Phase 1 + Phase 2）
- assembleDebugAndroidTest androidTest 编译

### 9.4 人工审查（等所有 Phase 完成后统一）

- 情绪状态数据模型与 DB 迁移
- Engine 计算逻辑（漂移/积累/衰减/基线）
- BoredInitiator 门控与 PromptBuilder 注入集成

## 十、风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| LLM 不输出 emotionIntensity | 势能积累失效 | 默认值 0，势能靠静默积累兜底；PromptBuilder Zone C 明确说明字段 |
| 势能长期过高刷屏 | 用户烦扰 | 发起后 -30 消耗 + 每小时 -2 衰减 + 门控阈值 |
| 势能长期过低 Agent 不主动 | 体验冷淡 | 静默积累每小时 +5，4 小时即达 20 阈值；睡眠基线只影响 valence 不影响势能基础积累 |
| valence 持续负值 Agent 沉默 | 体验差 | 用户发消息后 LLM 自报 intensity 回正 valence；每小时 valence 向 0 漂移 0.05 |
| 睡眠基线数据缺失 | 基线失效 | yesterdayState == null 时用中性默认值，不崩溃 |
| 与 Phase 2 关系系统数值冲突 | 行为异常 | 完全解耦：关系系统管亲密度/信任度/回复延迟，情绪系统管 valence/arousal/势能/主动发起门控，互不干扰 |

## 十一、与现有系统的关系

### 11.1 与 Phase 1（分级睡眠）

- Phase 3 兑现 Phase 1 推迟的"睡眠质量→今天心情"承诺
- SleepPhaseSplitter 产生的 sleepDurationMin 通过 daily_state 流入 EmotionalEngine.applySleepBaseline
- LIGHT_SLEEP 状态下情绪仍正常积累（不特殊处理）

### 11.2 与 Phase 2（关系系统）

- **完全解耦**：RelationshipState 管 intimacyScore/trustScore/interactionCount，EmotionalState 管 valence/arousal/potentialEnergy
- 关系系统影响回复延迟系数，情绪系统影响主动发起门控和语气
- 两者都通过 ChatInteractor.onTurnCompleted 触发，但各自独立更新
- AgentConfigExporter 不导出 EmotionalState（情绪是运行时状态，非 Agent 人格）

### 11.3 与 BoredInitiator v2

- 保留现有三层决策架构（预筛选 → Think → Act）
- Phase 3 只在预筛选层追加势能门控，不动 Think/Act 层
- 势能 ≥ 80 时绕过的是预筛选层的 30 分钟冷却，Think 层的 prior_attempts 判断仍保留
