# 如何设计一个有状态的 AI 陪伴系统：Ta 的技术实践

> 本文记录我们在 Android 平台上构建一个长期、有记忆、有作息的 AI 陪伴 Agent 的技术思路与实现方案。  
> 开源地址：[github.com/strive-zwy/Ta](https://github.com/strive-zwy/Ta) · 官网：[ta-ayh.pages.dev](https://ta-ayh.pages.dev)

---

## 背景：为什么需要"有状态"的 AI？

当前大多数 AI 对话工具本质上是**无状态的请求-响应模型**：用户发送一条消息，模型返回一条回复，对话上下文由客户端拼接后每次完整发送。这种模式在处理一次性问答时很高效，但在需要长期陪伴的场景下存在几个明显问题：

1. **没有长期记忆**：每次对话都是"新认识"，无法积累共同经历
2. **没有性格延续**：模型不会因为相处时间长而改变表达方式
3. **没有时间感**：不区分白天黑夜、忙碌空闲，始终以相同节奏回复
4. **人格表达不稳定**：依赖 prompt 中的人设描述，容易漂移或机械复读

**Ta** 是我们在 Android 平台上对这个问题的一次实践探索。下面从架构设计角度分享我们的解决方案。

---

## 整体架构

Ta 采用**单 Activity + Jetpack Compose + 本地优先**的分层架构：

```
Compose UI / ViewModel / Navigation
    │
    ▼
ChatInteractor / AgentEngine / ToolRegistry
    │
    ▼
Prompt / Persona / Memory / Relationship / Emotion
    │
    ▼
StateMachine / DailySchedule / AlarmManager / ForegroundService
    │
    ▼
Room / EncryptedSharedPreferences / Retrofit / OkHttp / Media3
```

### 技术栈选型

| 层次 | 技术选型 | 选型理由 |
|------|----------|----------|
| UI | Jetpack Compose + Material 3 | 声明式 UI，适合聊天界面快速迭代 |
| 本地存储 | Room 2.7.1 | Google 官方推荐，支持复杂查询和数据关系 |
| 数据序列化 | Kotlinx Serialization | Kotlin 原生支持，编译期类型安全 |
| 网络通信 | Retrofit 2.11.0 + OkHttp 4.12.0 | 成熟稳定，支持拦截器和连接池 |
| 语音播放 | AndroidX Media3 / ExoPlayer | Google 官方播放器，支持流式音频 |
| 安全 | EncryptedSharedPreferences | 使用 Android Keystore 加密 API Key |
| 后台调度 | AlarmManager + Foreground Service | 保证 Agent 作息和提醒在后台稳定运行 |

---

## 核心模块设计

### 1. 人格引擎（Persona Engine）

**问题**：如何让 Agent 的表达既符合人设，又不显得机械复读？

**方案**：我们没有简单地把人设拼进 prompt，而是设计了一套**动态人格激活机制**：

- 将人格特征拆解为可独立激活的标签（如"温柔""幽默""认真"）
- 根据当前对话话题，通过语义匹配动态激活相关特征，抑制无关特征
- 设置**表达预算**限制口头禅、标志性比喻的出现频率，避免机械化

```kotlin
// 简化示例：人格特征激活逻辑
class PersonaEngine {
    fun activateFeatures(topic: String, persona: Persona): List<Feature> {
        return persona.features
            .filter { it.relevanceTo(topic) > THRESHOLD }
            .sortedByDescending { it.strength }
            .take(MAX_ACTIVE_FEATURES)
    }
    
    fun applyBudget(features: List<Feature>, context: String): String {
        // 限制口头禅密度，避免在短回复中重复出现
        return context.applyExpressionBudget(features)
    }
}
```

### 2. 记忆系统（Memory System）

**问题**：Agent 如何记住之前的对话和共同经历？

**方案**：设计了**分层记忆结构**：

| 记忆类型 | 存储内容 | 生命周期 |
|----------|----------|----------|
| 短期记忆 | 最近 N 轮对话原文 | 会话期间 |
| 长期记忆 | 用户偏好、重要事件、关系里程碑 | 永久 |
| 摘要记忆 | 定期将长对话压缩为结构化摘要 | 永久 |

当上下文过长时，系统自动将早期对话压缩为摘要，保留关键信息的同时控制 token 消耗。

```kotlin
// 记忆摘要流程
suspend fun summarizeConversation(messages: List<Message>): Summary {
    val keyPoints = extractKeyPoints(messages)
    val relationships = extractRelationshipChanges(messages)
    val commitments = extractCommitments(messages)
    return Summary(keyPoints, relationships, commitments)
}
```

### 3. 动态作息系统（DailySchedule）

**问题**：如何让 Agent 拥有"生活感"，而不是随时回复？

**方案**：基于**状态机 + AlarmManager** 实现：

```
┌──────────┐     ┌──────────┐     ┌──────────┐
│  起床     │ ──→ │  活跃     │ ──→ │  忙碌     │
└──────────┘     └──────────┘     └──────────┘
                        │
                        ▼
                  ┌──────────┐
                  │  空闲     │ ──→ 回到活跃
                  └──────────┘
```

- 每天首次启动时，LLM 根据 Agent 人格和近期聊天生成当天作息计划
- 状态机驱动活动切换（起床 → 活跃 → 忙碌 → 空闲 → 休息）
- LLM 不可用时使用本地兜底作息，模型恢复后重新规划
- 用户发送消息时，根据当前状态决定回复策略（立即回复 / 排队等待 / 稍后补充）

### 4. 语音系统

**问题**：如何让 Agent 的声音自然、有情绪，且稳定可靠？

**方案**：设计了**三层音色定制 + 情绪化表达 + 容错链路**：

**音色定制**：
- **音色复刻**：上传参考音频 → 通过远程 TTS 接口克隆音色
- **音色设计**：文字描述音色特征 → 按描述生成
- **预置音色**：使用内置精品音色作为默认

**情绪化表达**：
- 为 neutral / happy / calm 三种情绪分别配置独立声音样本
- Agent 回复时通过 `emotion` 字段判断情绪，按对应情绪取用声音
- 未单独配置的情绪自动回退到中性样本

**播放体验优化**：
- 逐条合成、逐条发送，不必等整批结束
- 尊重标点断句，优先按句号、问号、感叹号停顿
- 只读该读的文字（Emoji、动作描述等不进入语音）
- 固定基线语速，不因上下文忽快忽慢
- 不自动播放，点击才播放

**容错链路**：
```
MiMo 语音克隆 → 失败 → Android 系统 TTS → 失败 → 纯文本消息
```
确保对话不会因语音合成失败而中断。

### 5. 数据隐私设计

**原则**：数据尽量留在本地。

- Agent 配置、聊天与记忆存储在设备 Room 数据库
- API Key 使用 `EncryptedSharedPreferences` 本地加密保存
- 分享的 `.agent.zip` 配置包**不包含** API Key、模型地址或模型名称
- 调用第三方 LLM/TTS 服务时，相关请求受对应服务隐私政策约束

---

## 技术难点与思考

### 难点 1：人格一致性 vs 表达自然性

过于严格的人设约束会导致表达僵硬，过于宽松会导致人设漂移。我们的解决思路是：
- 人格特征作为**软约束**，通过激活机制和表达预算控制密度
- 允许在不违背核心人设的前提下自由表达
- 通过长期记忆和关系阶段让人设**自然演化**，而非固定不变

### 难点 2：有状态 vs 无状态模型的权衡

大模型本身是无状态的，我们需要在应用层实现状态管理。这带来两个挑战：
- **上下文膨胀**：长期记忆累积会导致 token 消耗快速增长 → 通过摘要机制压缩
- **状态一致性**：多端同步时状态可能冲突 → 目前以单设备本地优先为主

### 难点 3：后台耗电与功能稳定的平衡

Agent 需要后台运行来驱动作息和提醒，但 Android 厂商后台限制越来越严格。我们的应对：
- 使用 Foreground Service + 通知渠道
- 引导用户关闭电池优化和自启动
- 不同厂商 ROM 提供差异化引导路径

---

## 项目开源

本项目已在 GitHub 开源，采用 MIT License：

- **仓库地址**：[github.com/strive-zwy/Ta](https://github.com/strive-zwy/Ta)
- **官网**：[ta-ayh.pages.dev](https://ta-ayh.pages.dev)
- **技术栈**：Kotlin + Jetpack Compose + Room + Retrofit + Media3
- **最低版本**：Android 7.0 (API 24)
- **编译版本**：API 37

如果你对这个项目的技术实现感兴趣，欢迎在 GitHub 上查看源码、提交 Issue 或 Pull Request。
