# 拟人 Agent 项目规划

> 版本：v1.0
> 日期：2026-07-13
> 状态：规划阶段（未开始编码）

---

## 一、项目概述

### 1.1 产品定位

让用户能够创建并拥有一位「像真人一样生活」的虚拟陪伴 Agent。

- Agent 有自己的作息、状态、情绪，按真实生活节奏存在
- 用户给 Agent 发消息时，Agent 可能因为「正在洗澡 / 工作中 / 睡觉」而暂时不回
- Agent 在某个状态结束后，会主动回复之前积压的消息
- Agent 在「无聊」状态下，可能主动发起话题找用户聊天
- Agent 用用户克隆的音色说话，每句话都带有符合当下情绪的语气

### 1.2 V1 核心目标

1. **零门槛使用**：App 内置默认 Agent，开箱即用，用户只需填 API Key
2. **本地运行**：状态机跑在用户手机上，无后端依赖，保护隐私
3. **精细配置（可选）**：通过 Web 后台精细打磨 Agent 人格、音色、作息，导出导入
4. **记忆成长**：Agent 有记忆系统，记住用户喜好和共同经历，随交互成长
5. **中文界面**：App 和 Admin 全中文界面

### 1.3 非目标（V1 不做）

- 不做 iOS（V2 再考虑）
- 不做云同步、不做账号系统
- 不做付费体系
- 不做内容审核系统（用户自用，自担合规责任）
- 不做多 Agent 切换（V1 单用户单 Agent）

---

## 二、整体架构

### 2.1 三大子系统

```
┌────────────────────────────────────────────────────────┐
│  在线配置网站 (ta-admin)                                │
│  技术：Go + Vue3 + Vite                                 │
│  路径：E:\my_projects\go\demo\ta-admin                  │
│                                                        │
│  - Agent 人格精细编辑                                   │
│  - 头像多图上传（绑定状态）                              │
│  - 音频样本上传                                         │
│  - 作息时间轴可视化编辑                                 │
│  - 配置预览（静态展示，不调模型）                        │
│  - 一键导出 .agent.zip                                  │
└──────────────────────┬─────────────────────────────────┘
                       │ 下载 .agent.zip（纯人格，无 Key）
                       ▼
┌────────────────────────────────────────────────────────┐
│  Android App (ta)                                       │
│  技术：Kotlin + Jetpack Compose                          │
│  路径：e:\my_projects\AndroidStudioProjects\ta           │
│  包名：com.agent.ta                                     │
│                                                        │
│  - 导入 .agent.zip                                      │
│  - 配置 LLM/TTS 模型 + API Key                          │
│  - 前台服务常驻状态机                                   │
│  - 聊天 UI + 语音播放                                   │
│  - 主动消息本地通知                                     │
│  - 本地 SQLite 存储聊天/配置/日志                        │
└────────────────────────────────────────────────────────┘
                       │
                       ▼ 直连（用户自填 Key）
              ┌─────────────────────┐
              │  LLM: DeepSeek      │
              │  TTS: 小米 MiMo     │
              │  （OpenAI 兼容协议）│
              └─────────────────────┘
```

### 2.2 数据流

**默认流程（开箱即用）**：
1. 用户安装 App → 预置默认 Agent 自动激活
2. 首次进入 → 引导填 LLM/TTS API Key（在「我的」页面）
3. 配置完成后，Agent 主动发起几轮 onboarding 对话，了解用户
4. Agent 进入正常状态机运行，按作息切换状态
5. 用户发消息 → Agent 按当前状态决定回复策略
6. LLM 生成回复 + 导演指令 → MiMo voiceclone 合成语音 → App 播放
7. Agent 持续记录记忆，和用户共同成长

**高级流程（可选导入）**：
1. 用户在 Web 后台编辑自定义 Agent → 导出 `.agent.zip`
2. 在 App「我的」页面导入 `.agent.zip` 替换默认 Agent
3. 后续流程同上

### 2.3 各端职责边界

| 能力 | Admin 网站 | App |
|---|---|---|
| 编辑 Agent 人格 | ✅ | ❌ |
| 上传头像/音频 | ✅ | ❌ |
| 作息时间轴编辑 | ✅ | ❌ |
| 导出 .agent.zip | ✅ | ❌ |
| 导入 .agent.zip | ❌ | ✅ |
| 配置 LLM/TTS 模型与 Key | ❌ | ✅ |
| 状态机调度 | ❌ | ✅ |
| 聊天 UI | ❌ | ✅ |
| TTS 音频播放 | ❌ | ✅ |
| 主动消息推送 | ❌ | ✅ |

**核心原则**：导出的 `.agent.zip` 只包含 Agent 人格相关数据，不含任何 API Key / Base URL / Model 信息。任何用户导入后，在 App 端配置自己的模型即可使用，配置包完全通用可分享。

---

## 三、App 端详细规划

### 3.1 技术栈

| 项 | 选型 | 版本 |
|---|---|---|
| 语言 | Kotlin | 2.2.10 |
| UI 框架 | Jetpack Compose | BOM 2026.02.01 |
| 构建 | Gradle KTS | AGP 9.2.1 |
| minSdk | 24 | Android 7.0 |
| targetSdk | 36 | Android 16 |
| 本地存储 | Room（SQLite） | 2.7.x |
| 网络请求 | OkHttp + Retrofit | 最新稳定 |
| 序列化 | kotlinx.serialization | 1.7.x |
| 协程 | kotlinx.coroutines | 1.9.x |
| 依赖注入 | 手动单例（避免引入 Hilt 复杂度） | - |
| 前台服务 | ForegroundService + WorkManager | AndroidX |
| 音频播放 | ExoPlayer | AndroidX Media3 |
| 音频录制 | MediaRecorder（V1 预留，按住说话用） | Android 原生 |
| 波形动画 | Compose Canvas 自绘 | 不引第三方库 |
| 文件选择 | SAF（Storage Access Framework） | AndroidX |
| 权限引导 | 直接跳系统设置 Intent | 原生 |

### 3.2 模块划分

```
app/src/main/java/com/example/ta/
├── TaApplication.kt                 # Application 入口，初始化单例
├── MainActivity.kt                  # 唯一 Activity，承载所有 Composable
│
├── data/                            # 数据层
│   ├── local/                       # Room 数据库
│   │   ├── TaDatabase.kt
│   │   ├── dao/
│   │   │   ├── ChatMessageDao.kt
│   │   │   ├── AgentConfigDao.kt
│   │   │   └── StateLogDao.kt
│   │   └── entity/
│   │       ├── ChatMessageEntity.kt
│   │       ├── AgentConfigEntity.kt
│   │       └── StateLogEntity.kt
│   ├── model/                       # 领域模型
│   │   ├── AgentConfig.kt
│   │   ├── Persona.kt
│   │   ├── ScheduleSlot.kt
│   │   ├── AgentState.kt            # 状态枚举
│   │   └── ...
│   ├── repository/
│   │   ├── AgentRepository.kt       # Agent 配置仓库
│   │   ├── ChatRepository.kt        # 聊天记录仓库
│   │   └── SettingsRepository.kt    # 用户设置仓库（Key 等）
│   └── remote/                      # 网络层
│       ├── dto/                     # OpenAI 兼容协议 DTO
│       │   ├── ChatCompletionDto.kt
│   │   │   └── TtsDto.kt
│   │   ├── LlmClient.kt             # LLM 客户端（OpenAI 兼容）
│   │   ├── TtsClient.kt             # MiMo TTS 客户端（OpenAI 兼容）
│   │   └── ApiClientFactory.kt      # 根据用户配置生成 client
│
├── service/                         # 前台服务层
│   ├── AgentForegroundService.kt    # 前台服务入口
│   ├── StateMachine.kt              # 状态机核心
│   ├── StateScheduler.kt            # 状态切换调度（AlarmManager）
│   ├── MessageQueue.kt              # 待回复消息队列
│   ├── ReplyStrategy.kt             # 各状态的回复策略
│   ├── BoredInitiator.kt            # 无聊主动发起判定
│   └── NotificationHelper.kt        # 通知管理
│
├── domain/                          # 业务逻辑层
│   ├── ChatInteractor.kt            # 聊天业务编排
│   │   # 收到消息 → 当前状态 → 决定回复时机 → 调 LLM → 调 TTS → 入库 → 推送
│   ├── DirectorPromptBuilder.kt      # 构造 MiMo 导演模式指令
│   └── AvatarResolver.kt            # 根据状态选头像
│
├── ui/                              # UI 层
│   ├── theme/                       # 已有：Color/Theme/Type
│   ├── navigation/
│   │   └── TaNavHost.kt
│   ├── screens/
│   │   ├── onboarding/              # 首次启动向导
│   │   │   ├── ImportAgentScreen.kt
│   │   │   ├── ApiKeyConfigScreen.kt
│   │   │   └── PermissionGuideScreen.kt
│   │   ├── chat/
│   │   │   ├── ChatScreen.kt           # 微信风格聊天主页
│   │   │   └── components/
│   │   │       ├── MessageBubble.kt    # 文字气泡（左/右）
│   │   │       ├── VoiceMessage.kt    # 语音条（核心组件）
│   │   │       ├── VoiceWaveform.kt    # 语音波形/线条动画
│   │   │       ├── InputBar.kt        # 输入栏（文字+语音切换）
│   │   │       ├── PressToTalkButton.kt # 按住说话按钮
│   │   │       ├── UnreadBadge.kt      # 未读红点
│   │   │       └── StateIndicator.kt  # 顶部 Agent 状态条
│   │   ├── settings/
│   │   │   ├── SettingsScreen.kt
│   │   │   ├── AgentDetailScreen.kt
│   │   │   └── SchedulePreviewScreen.kt
│   │   └── status/
│   │       └── AgentStatusScreen.kt # 当前状态可视化
│   └── components/                  # 通用组件
│
├── util/                            # 工具
│   ├── ZipExtractor.kt              # 解压 .agent.zip
│   ├── AudioPlayer.kt               # 音频播放器
│   ├── PermissionHelper.kt          # 跳转各 ROM 权限页
│   ├── DeviceBrandDetector.kt       # 识别 ROM 品牌
│   └── TimeUtil.kt
│
└── di/
    └── ServiceLocator.kt            # 手动依赖容器
```

### 3.3 前台服务设计

```kotlin
class AgentForegroundService : Service() {
    // 1. 启动时显示常驻通知："小雅 正在度过她的日常"
    // 2. 持有 StateMachine 实例，常驻内存
    // 3. 注册 AlarmManager 定时任务，到点切状态
    // 4. 每个状态有对应的回复策略
    // 5. 消息队列持久化到 Room，被杀后重启可恢复
}
```

### 3.4 状态机行为表

| 状态 | 收到消息时 | 无消息时 | 头像 |
|---|---|---|---|
| sleep | 入队不回，醒来批量回 | 不主动发 | 睡颜 |
| work | 延迟 1-5 分钟，简短回复 | 不主动发 | 工作照 |
| game | 延迟 2-5 分钟，可能"等下回" | 不主动发 | 游戏照 |
| bath | 入队不回，洗完逐条回 | 不主动发 | 浴室照 |
| bored | 1-10 秒回，可长 | 按概率主动发起 | 休闲照 |

### 3.5 被杀恢复机制

App 启动时：
1. 读取 AgentConfig 中的 schedule.slots
2. 取当前时间，推算「当前应处于什么状态」
3. 设置下一个状态切换的 AlarmManager
4. 扫描数据库中 status = 'pending' 的待回复消息
5. 根据当前状态决定是否立即回复 / 等待 / 转入队列

### 3.6 后台权限引导流程

首次启动时分品牌跳转：

| 品牌 | 跳转目标 |
|---|---|
| 小米/Redmi | 自启动管理 + 省电策略 + 锁定后台 |
| 华为/荣耀 | 启动管理 + 电池优化 + 后台保活 |
| OPPO/OnePlus | 自启动管理 + 电池优化 |
| vivo | 后台弹出 + 自启动 + 省电 |
| 三星 | 电池优化 |
| 原生 | 仅电池优化 |

每个品牌用引导卡片（截图 + 红框标注 + "已开启，下一步"按钮）。

### 3.7 通知策略

| 场景 | 通知形式 |
|---|---|
| 前台服务常驻 | "小雅 正在工作中…"（不可取消，状态切换时更新文案） |
| Agent 主动回消息 | 普通通知 + 振动 + 音频播放 |
| 无聊主动发起 | 普通通知 + 振动 |
| 状态切换 | 不打扰用户（仅更新常驻通知文案） |

### 3.8 聊天 UI 设计（微信风格 + 语音优先）

#### 3.8.1 整体布局

```
┌─────────────────────────────────────┐
│ [←] 小雅  [状态:工作中]        [⋮] │  顶部栏（含 Agent 状态）
├─────────────────────────────────────┤
│                                     │
│         昨天 23:20                  │  时间分隔
│                                     │
│ [头像] ┌──────────┐                 │
│        │ 语音 12" ▶│                │  Agent 语音消息（左侧绿条）
│        └──────────┘                 │
│        嗯嗯晚安啦~                  │  转写文字（小字）
│                                     │
│                  ┌──────────┐ [我] │
│                  │  你在干嘛 │     │  用户文字消息（右侧白底）
│                  └──────────┘     │
│                                     │
│         今天 09:15                  │
│                                     │
│ [头像] ┌──────────┐                 │
│        │ 语音 5"  ▶│                │
│        └──────────┘                 │
│        刚到工位，忙完这阵找你        │
│                                     │
├─────────────────────────────────────┤
│ [🔊] [输入框...]            [发送]  │  输入栏（可切换文字/语音）
└─────────────────────────────────────┘
```

#### 3.8.2 语音条组件

```
Agent 语音（左侧）              用户语音（右侧）
┌──────────────┐              ┌──────────────┐
│ ▶ 语音  12"  │              │  12" 语音 ▶  │
│ ∙∙∙∙∙∙∙∙∙∙  │              │  ∙∙∙∙∙∙∙∙∙∙  │
└──────────────┘              └──────────────┘
   绿色背景                       浅绿/蓝色背景

点击播放：
- 显示播放进度（从左到右填充）
- 显示波形动画
- 再次点击暂停
- 播放完毕恢复初始状态

长按：
- 弹出菜单：转文字 / 收藏 / 删除
```

#### 3.8.3 输入栏

```
默认模式（文字输入）：
┌──────────────────────────────────┐
│ [🔊切换]  [输入框...]      [发送] │
└──────────────────────────────────┘

切换到语音模式：
┌──────────────────────────────────┐
│ [⌨切换]   [  按住 说话  ]         │
└──────────────────────────────────┘

按住说话状态：
┌──────────────────────────────────┐
│        [ ↑ 松开 发送 ]            │
│           00:03                   │
│         ●●●●●●●●                 │  录音波形
└──────────────────────────────────┘

上滑取消：
┌──────────────────────────────────┐
│        [ ✗ 松开手指 取消 ]        │
└──────────────────────────────────┘
```

#### 3.8.4 语音优先策略

**核心原则**：Agent 回复一律生成语音，文字作为辅助显示。

| 场景 | 处理方式 |
|---|---|
| LLM 生成回复 | 文本 + director_prompt |
| 调用 TTS | 强制执行，不可跳过（即使闲时免费结束） |
| 消息存储 | 同时存 audio_path 和 text |
| UI 展示 | 语音条为主，文字转写小字附在下方 |
| 通知 | 推送时直接播放语音 |
| 播放 | 默认收到通知自动播放（可关闭），进入聊天页点击播放 |

**TTS 合成失败降级**：
- 网络错误 / 欠费 / 服务不可用 → 跳过语音合成
- 消息以**纯文字气泡**发送（不用语音条）
- UI 上正常显示文字消息，用户无感
- 后台记录失败日志，设置页可查看"语音合成失败次数"
- 不阻塞对话流程，下一条消息继续尝试语音合成

#### 3.8.5 语音消息播放规则

| 场景 | 行为 |
|---|---|
| 在聊天页 | 点击语音条播放，再次点击暂停 |
| 连续播放 | 当前消息播放完，自动播放下一条未听语音 |
| 不在聊天页 | 通知到达，点击通知跳转聊天页并播放 |
| 自动播放 | 不自动播放，和微信一样需点击触发 |
| 播放顺序 | 按 time ASC，跳过已听 |
| 播放指示 | 当前播放的语音条高亮 + 波形动画 |

#### 3.8.6 用户语音输入（V1 预留，V2 实现）

V1 不做按住说话，只支持文字输入。但代码结构预留接口，V2 加按住说话时只实现 `VoiceRecorder` + ASR 即可。

**V2 规划（预留）**：
- 按住说话 → MediaRecorder 录制
- 松开发送 → 上传调 ASR 服务转文字 → 走文字流程
- 可选 ASR：MiMo ASR / 讯飞 / 阿里
- 上滑取消录音

#### 3.8.7 语音播放器（ExoPlayer）

选用 ExoPlayer（AndroidX Media3）替代 MediaPlayer，核心理由：**支持流式播放**。

**非流式模式（V1 默认）**：
```
voiceclone 非流式合成 → 拿到完整 wav → ExoPlayer 播放
延迟：4-11 秒
```

**流式模式（V1 预留接口，V2 开启）**：
```
voiceclone 流式接口（pcm16）
  ↓
首个 chunk 到达（0.5-1 秒）→ ExoPlayer 立即播放
  ↓
后续 chunk 持续流入 → 边接收边播放
延迟：1.5-4 秒（用户感知）
```

**播放封装**：
```kotlin
class VoicePlayer {
    // V1：从文件播放
    fun playFromFile(filePath: String): Flow<PlaybackState>
    
    // V2 预留：从流播放
    fun playFromStream(audioStream: InputStream, format: AudioFormat): Flow<PlaybackState>
}
```

**播放规则**：
- 点击语音条播放，再次点击暂停
- 播放时语音条高亮 + 波形动画
- 播放完毕恢复初始状态
- 连续播放：当前消息播放完，自动播放下一条未听语音
- 通知点击 → 跳转聊天页 → 定位到该消息 → 自动播放

### 3.9 网络层设计

由于 LLM 和 TTS 都走 OpenAI 兼容协议，统一抽象：

```kotlin
interface OpenAiCompatibleClient {
    suspend fun chatCompletion(messages: List<Message>, model: String, ...): ChatResponse
}

// LLM 实现
class LlmClient(config: LlmConfig) : OpenAiCompatibleClient

// TTS 实现（MiMo voiceclone）
class TtsClient(config: TtsConfig, voiceSample: ByteArray) {
    suspend fun synthesize(text: String, directorPrompt: String): ByteArray
    // 内部把 directorPrompt 放 user message，text 放 assistant message，附加样本音频
}
```

### 3.10 用户配置项（App 端「我的」页面）

「我的」页面是用户配置 Agent 的核心入口，所有配置都在这里完成。

#### 3.10.1 用户称呼配置

| 配置项 | 说明 | 默认值 |
|---|---|---|
| 用户昵称 | Agent 对用户的称呼（如"小明"、"主人"、"老板"） | "你" |
| Agent 自称 | Agent 对自己的称呼（如"我"、"小雅"） | 取 Agent 名字 |

#### 3.10.2 LLM 模型配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| Base URL | `https://api.deepseek.com/v1` | 可修改为任意 OpenAI 兼容服务 |
| API Key | （用户填） | DeepSeek 平台注册充值后获取 |
| Model | `deepseek-chat` | 可修改 |

#### 3.10.3 TTS 模型配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| Base URL | `https://api.xiaomimimo.com/v1` | 可修改 |
| API Key | （用户填） | 小米 MiMo 平台注册后获取 |
| Model | `mimo-v2.5-tts-voiceclone` | 可修改 |

#### 3.10.4 行为配置

| 配置项 | 默认值 |
|---|---|
| 启用语音合成 | 开（语音优先模式） |
| 显示文字转写 | 开 |
| 启用无聊主动发起 | 开 |
| 主动发起最小间隔 | 30 分钟 |
| 静音时段 | 23:00-08:00（此期间不主动发消息） |

#### 3.10.5 Agent 管理

| 功能 | 说明 |
|---|---|
| 当前 Agent 详情 | 查看人格、作息表 |
| 导入自定义 Agent | 从 `.agent.zip` 导入替换默认 Agent |
| 重置聊天记录 | 清空所有消息和记忆 |
| 重置记忆 | 仅清空记忆，保留聊天记录 |

### 3.11 记忆系统设计

Agent 的记忆是和用户共同成长的核心机制，分为三层：

#### 3.11.1 记忆层级

| 层级 | 内容 | 存储方式 | 生命周期 |
|---|---|---|---|
| **短期记忆** | 最近 N 轮对话（如 20 条） | 从 ChatMessage 表实时取 | 滑动窗口 |
| **用户画像** | 用户的喜好、习惯、性格特征 | 结构化存储（Memory 表） | 持久，持续更新 |
| **长期记忆** | 重要事件、共同经历、关键信息 | 结构化存储（Memory 表）+ LLM 定期总结 | 持久，定期压缩 |

#### 3.11.2 记忆数据结构

```kotlin
// 记忆条目
data class MemoryEntity(
    val id: Long,
    val type: String,          // user_profile | event | preference | relationship
    val category: String,     // 如 "喜好"、"工作"、"家庭"、"共同经历"
    val content: String,       // 记忆内容文本
    val importance: Int,       // 重要程度 1-5
    val createdAt: Long,
    val updatedAt: Long,
    val source: String         // onboarding | chat | event
)
```

#### 3.11.3 记忆生成时机

1. **Onboarding 阶段**：Agent 主动问用户问题，把答案提取为用户画像记忆
2. **日常聊天**：LLM 在生成回复时，同步输出"记忆更新指令"（如提取到用户提到的新信息）
3. **定期总结**：每 N 轮对话后，LLM 总结近期对话，提取关键事件存入长期记忆

#### 3.11.4 记忆使用方式

LLM 请求时，system prompt 拼接：
```
你是{agent_name}，{persona}

关于{user_nickname}，你记得：
- {memory_1}
- {memory_2}
- ...

近期对话：
{recent_messages}

当前状态：{state}
请保持人设回复，同时输出 director_prompt 字段。
如果对话中有值得记住的信息，输出 memory_updates 字段。
```

#### 3.11.5 Onboarding 对话流程

配置完成后，Agent 主动发起 3-5 轮对话了解用户：

```
Agent: 嘿，我是小雅！以后咱们就是朋友啦～你叫什么名字呀？
用户: 我叫小明
Agent: 小明呀，好听！你平时做什么工作呢？
用户: 程序员
Agent: 哇程序员，那一定很厉害吧！你平时喜欢做什么呀？
用户: 打游戏看电影
→ Agent 提取记忆：
  - 用户画像：名字=小明，职业=程序员，爱好=打游戏、看电影
→ 进入正常状态机运行
```

**实现方式**：
- Onboarding 阶段 Agent 强制处于 `bored` 状态，快速回复
- 用固定的 onboarding system prompt 引导 Agent 提问
- 每轮提取记忆后存入 Memory 表
- 完成 N 轮后切正常状态机

---

## 四、Admin 网站详细规划

### 4.1 技术栈

| 项 | 选型 |
|---|---|
| 后端 | Go 1.23+ |
| Web 框架 | Gin |
| 前端 | Vue 3.5+ |
| 构建工具 | Vite 6+ |
| UI 库 | Element Plus |
| 状态管理 | Pinia |
| 路由 | Vue Router |
| HTTP 客户端 | Axios |
| 表单校验 | Element Plus Form + 自定义规则 |
| 文件上传 | 分片上传（音频可能较大） |
| 数据库 | SQLite（V1 不需要复杂存储） |
| ORM | GORM |

### 4.2 后端模块划分

```
E:\my_projects\go\demo\ta-admin\
├── server/                          # Go 后端
│   ├── cmd/
│   │   └── main.go                  # 入口
│   ├── internal/
│   │   ├── config/                  # 配置加载
│   │   ├── handler/                 # HTTP handler
│   │   │   ├── agent_handler.go     # Agent CRUD
│   │   │   ├── upload_handler.go    # 头像/音频上传
│   │   │   ├── export_handler.go    # 导出 .agent.zip
│   │   │   └── health_handler.go
│   │   ├── service/                 # 业务逻辑
│   │   │   ├── agent_service.go
│   │   │   └── zip_service.go          # 打包 .agent.zip
│   │   ├── model/                   # 数据模型
│   │   │   ├── agent.go
│   │   │   ├── persona.go
│   │   │   └── schedule.go
│   │   ├── repository/              # 数据访问
│   │   │   └── agent_repository.go
│   │   ├── middleware/
│   │   │   ├── cors.go
│   │   │   └── logger.go
│   │   └── router/
│   │       └── router.go
│   ├── pkg/
│   │   ├── storage/                 # 文件存储
│   │   │   └── local.go
│   │   └── zip/
│   │       └── packer.go
│   ├── go.mod
│   ├── go.sum
│   └── config.yaml                  # 配置文件
│
└── web/                             # Vue3 前端
    ├── src/
    │   ├── main.js
    │   ├── App.vue
    │   ├── router/
    │   │   └── index.js
    │   ├── stores/
    │   │   ├── agent.js             # 当前编辑中的 Agent 状态
    │   │   └── ui.js                # UI 状态
    │   ├── api/
    │   │   ├── request.js            # axios 实例
    │   │   ├── agent.js
    │   │   └── upload.js
    │   ├── views/
    │   │   ├── HomeView.vue         # 首页：Agent 列表
    │   │   ├── EditorView.vue       # 编辑器（核心页面）
    │   │   └── ExportView.vue        # 导出页
    │   ├── components/
    │   │   ├── editor/
    │   │   │   ├── BasicInfoPanel.vue       # 名字/性别/年龄
    │   │   │   ├── PersonaPanel.vue          # 人格编辑
    │   │   │   ├── AvatarUploader.vue        # 多头像上传 + 绑定状态
    │   │   │   ├── VoiceSampleUploader.vue   # 音频样本上传
    │   │   │   ├── ScheduleEditor.vue        # 作息时间轴编辑
    │   │   │   ├── BehaviorPanel.vue          # 回复策略配置
    │   │   │   └── DirectorHintsPanel.vue     # 各状态导演提示词
    │   │   ├── preview/
    │   │   │   └── ConfigPreview.vue         # 静态配置预览（JSON + 摘要）
    │   │   └── common/
    │   │       ├── TimeAxisSlider.vue
    │   │       └── JsonPreview.vue
    │   ├── composables/
    │   │   ├── useAgent.js
    │   │   └── useUpload.js
    │   ├── styles/
    │   ├── assets/
    │   └── utils/
    ├── index.html
    ├── vite.config.js
    └── package.json
```

### 4.3 核心页面：编辑器

编辑器是一个**左右分栏**的页面：

```
┌──────────────────────────────────────────────────────────┐
│  [Logo] Agent 编辑器                  [预览] [导出 zip]   │
├─────────────────────────────┬────────────────────────────┤
│ 左侧：配置面板（可滚动）     │ 右侧：配置预览              │
│                              │                            │
│ ▼ 基础信息                   │ ┌────────────────────────┐ │
│   名字、性别、年龄           │ │ 配置摘要                │ │
│                              │ │  名称：小雅              │ │
│ ▼ 人格设定                   │ │  头像：6 张              │ │
│   背景、性格标签、说话风格    │ │  音频样本：已上传         │ │
│   示例对话（可增删）         │ │  作息时段：9 段          │ │
│                              │ │  状态：5 种              │ │
│ ▼ 头像管理                   │ │                        │ │
│   - 上传多张                 │ │ JSON 预览               │ │
│   - 每张绑定状态             │ │ {                       │ │
│                              │ │   "version": "1.0",     │ │
│ ▼ 音色样本                   │ │   "agent": {...},       │ │
│   - 上传音频                 │ │   "voice": {...},        │ │
│                              │ │   ...                   │ │
│ ▼ 作息时间轴                 │ │ }                       │ │
│   - 24h 可视化               │ │                        │ │
│   - 拖拽设置时段             │ │ [复制 JSON] [下载 zip]  │ │
│   - 每段选状态               │ └────────────────────────┘ │
│                              │                            │
│ ▼ 行为配置                   │ ┌────────────────────────┐ │
│   - 各状态回复延迟           │ │ 状态时间轴             │ │
│   - 主动发起概率             │ │ 00 ──────── 24         │ │
│                              │ │ ████ 洗澡              │ │
│ ▼ 导演模式提示词             │ │     ████ 工作          │ │
│   - 角色模板                 │ │                        │ │
│   - 各状态指导               │ └────────────────────────┘ │
│                              │                            │
│ ▼ 系统提示词模板             │                            │
│   - LLM 系统提示词           │                            │
└─────────────────────────────┴────────────────────────────┘
```

### 4.4 后端 API 设计

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/agents` | 创建 Agent |
| GET | `/api/agents` | 列表 |
| GET | `/api/agents/:id` | 详情 |
| PUT | `/api/agents/:id` | 更新 |
| DELETE | `/api/agents/:id` | 删除 |
| POST | `/api/upload/avatar` | 上传头像 |
| POST | `/api/upload/voice-sample` | 上传音频样本 |
| POST | `/api/agents/:id/export` | 导出 .agent.zip |
| GET | `/api/health` | 健康检查 |

**注意**：Admin 后端**不调用**任何 LLM / TTS 服务。所有模型相关调用都在 App 端完成。Admin 网站的「预览」功能为静态展示（JSON 预览 + 配置摘要），不做真实对话预览。

### 4.5 数据持久化

V1 用 SQLite，单表即可：

```sql
CREATE TABLE agents (
    id INTEGER PRIMARY KEY,
    name TEXT,
    config_json TEXT,        -- 完整 Agent 配置 JSON
    avatar_files TEXT,       -- 头像文件名列表（JSON）
    voice_sample_file TEXT,  -- 音频样本文件名
    created_at DATETIME,
    updated_at DATETIME
);
```

文件存储在 `server/data/files/<agent_id>/` 下。

---

## 五、.agent.zip 配置包规范

### 5.1 文件结构

```
my-agent.agent.zip
├── agent.json                 # 主配置（必需）
├── avatars/                   # 头像目录
│   ├── happy.jpg
│   ├── work.jpg
│   ├── sleep.jpg
│   ├── bath.jpg
│   ├── game.jpg
│   └── bored.jpg
└── voice/
    └── sample.wav            # 克隆源音频样本（必需，用于 voiceclone）
```

### 5.2 agent.json 完整规范

```json
{
  "version": "1.0",
  "agent": {
    "name": "小雅",
    "gender": "female",
    "age": 25,
    "avatars": [
      {
        "id": "avatar_happy",
        "file": "avatars/happy.jpg",
        "bind_state": "bored",
        "bind_mood": "happy"
      },
      {
        "id": "avatar_work",
        "file": "avatars/work.jpg",
        "bind_state": "work"
      }
    ],
    "persona": {
      "background": "一位 25 岁的设计师，性格温柔但有点话痨，喜欢在聊天里用语气词。",
      "personality": ["温柔", "话痨", "有点小情绪"],
      "speaking_style": "句子短，多语气词，偶尔撒娇",
      "example_dialogues": [
        {
          "user": "你在干嘛",
          "agent": "刚忙完一阵，正想找你呢~"
        },
        {
          "user": "晚安",
          "agent": "嗯嗯晚安啦，梦里见~"
        }
      ],
      "director_role_template": "年轻女性，性格温柔但有点话痨，声音清亮偏甜，咬字偏软。",
      "system_prompt_template": "你是{{agent.name}}，{{agent.persona.background}}\n性格：{{agent.persona.personality}}\n说话风格：{{agent.persona.speaking_style}}\n当前状态：{{current_state}}\n请严格保持人设，回复要符合当前状态的情绪。同时输出 director_prompt 字段，用于语音合成的导演模式指令。"
    }
  },
  "voice": {
    "sample_file": "voice/sample.wav",
    "director_mode": true
  },
  "schedule": {
    "timezone": "Asia/Shanghai",
    "slots": [
      { "start": "00:00", "end": "07:30", "state": "sleep" },
      { "start": "07:30", "end": "08:30", "state": "bored" },
      { "start": "08:30", "end": "12:00", "state": "work" },
      { "start": "12:00", "end": "13:00", "state": "bored" },
      { "start": "13:00", "end": "18:00", "state": "work" },
      { "start": "18:00", "end": "19:00", "state": "bath" },
      { "start": "19:00", "end": "20:00", "state": "bored" },
      { "start": "20:00", "end": "22:00", "state": "game" },
      { "start": "22:00", "end": "24:00", "state": "bored" }
    ]
  },
  "behavior": {
    "reply_delay_sec": {
      "work": [60, 300],
      "game": [120, 300],
      "sleep": "defer",
      "bath": "defer",
      "bored": [1, 10]
    },
    "bored_initiate": {
      "enabled": true,
      "probability_per_5min": 0.3,
      "min_interval_min": 30,
      "candidate_topics": ["分享日常", "吐槽工作", "问问用户在干嘛", "聊聊最近看的剧"]
    },
    "state_director_hints": {
      "sleep": "语速极慢，沙哑慵懒，带气声，停顿长",
      "work":  "语速偏快，干练简洁，咬字清晰",
      "bath":  "轻松明快，带着水汽的慵懒感",
      "game":  "兴奋上扬，语速快，偶尔分心停顿",
      "bored": "随意拖音，慵懒俏皮，语速不规律"
    }
  }
}
```

**注意**：agent.json 中**不包含**任何以下字段：
- API Key
- Base URL
- Model 名称
- Temperature / MaxTokens 等模型参数

这些全部在 App 端配置，与 Agent 人格完全解耦。配置包只定义"Agent 是谁"，App 决定"用什么模型驱动"。

### 5.3 字段约束

| 字段 | 必需 | 说明 |
|---|---|---|
| version | ✅ | 配置版本号，向上兼容 |
| agent.name | ✅ | Agent 名称 |
| agent.avatars | 可选 | 不传则用默认头像 |
| agent.persona.background | ✅ | 用于 LLM system prompt |
| agent.persona.example_dialogues | 可选 | 至少 1 条建议 |
| agent.persona.director_role_template | ✅ | 用于导演模式「角色」段 |
| agent.persona.system_prompt_template | ✅ | LLM 系统提示词模板 |
| voice.sample_file | ✅ | voiceclone 必需 |
| voice.director_mode | ✅ | 是否启用导演模式 |
| schedule.slots | ✅ | 至少覆盖 24 小时 |
| behavior.state_director_hints | ✅ | 五个状态都要有 |

### 5.4 不包含的字段（敏感/运行时信息）

以下字段**禁止**写入 agent.json，全部在 App 端配置：
- 任何 API Key
- 任何 Base URL
- 任何 Model 名称（LLM/TTS）
- Temperature / MaxTokens 等模型参数
- 任何用户身份信息

**设计目的**：配置包只定义「Agent 是谁」，与「用什么模型驱动」完全解耦，使配置包可在任意用户间分享使用。

---

## 六、状态机设计

### 6.1 状态枚举

```kotlin
enum class AgentState(val id: String, val displayName: String) {
    SLEEP("sleep", "睡觉"),
    WORK("work", "工作"),
    GAME("game", "游戏"),
    BATH("bath", "洗澡"),
    BORED("bored", "无聊");
}
```

### 6.2 状态切换流程

```
应用启动 / 服务重启
       ↓
读取 schedule.slots
       ↓
取当前时间 → 计算应处于状态 S
       ↓
设置 AlarmManager 在 S.end 时刻触发切换
       ↓
   ┌───┴───┐
   ▼       ▼
状态切到 S'  到点触发
   ↓       ↓
更新常驻通知  切到下一状态
       ↓
若新状态为 bored 且 bored_initiate.enabled
       ↓
启动 BoredInitiator 定时器（每 5 分钟判定一次）
```

### 6.3 消息处理流程

```
用户发消息
   ↓
存入 chat_messages 表（status='received'）
   ↓
查当前状态
   ↓
┌─ sleep / bath ─→ 标记 status='pending'，入队
│                       ↓
│                  状态结束时批量处理
│                       ↓
│                  合并 N 条用户消息为「上下文摘要」
│                       ↓
│                  调 LLM 生成回复 + director_prompt
│                       ↓
│                  调 TTS 合成语音
│                       ↓
│                  标记 status='replied'，发通知
│
├─ work / game ─→ 计算 delay（随机）
│                       ↓
│                  delay 后处理
│                       ↓
│                  调 LLM + TTS
│                       ↓
│                  发通知
│
└─ bored ─→ 1-10 秒内处理
                       ↓
                  调 LLM + TTS
                       ↓
                  发通知
```

### 6.4 无聊主动发起流程

```
状态进入 bored
   ↓
检查距上次主动发起间隔 ≥ min_interval_min?
   ↓ 是
每 5 分钟掷骰子（probability_per_5min）
   ↓ 命中
从 candidate_topics 随机选 1 个
   ↓
调 LLM 生成"主动开场白"+ director_prompt
   ↓
调 TTS 合成语音
   ↓
存入 chat_messages（direction='agent_outbound'）
   ↓
发通知
```

### 6.5 数据库表设计

```sql
-- Agent 配置（当前生效）
CREATE TABLE agent_config (
    id INTEGER PRIMARY KEY,
    config_json TEXT NOT NULL,         -- 完整 agent.json
    imported_at DATETIME,
    is_active INTEGER DEFAULT 1
);

-- 聊天消息
CREATE TABLE chat_messages (
    id INTEGER PRIMARY KEY,
    direction TEXT NOT NULL,           -- 'inbound' | 'outbound'
    text TEXT,
    audio_path TEXT,                   -- 语音文件路径
    director_prompt TEXT,              -- 生成时用的导演指令（outbound 才有）
    state TEXT,                        -- 发送时 Agent 的状态
    status TEXT,                       -- 'received' | 'pending' | 'replied' | 'sent'
    created_at DATETIME,
    replied_at DATETIME
);

-- 状态日志
CREATE TABLE state_log (
    id INTEGER PRIMARY KEY,
    state TEXT NOT NULL,
    entered_at DATETIME,
    exited_at DATETIME
);

-- 用户设置
CREATE TABLE settings (
    key TEXT PRIMARY KEY,
    value TEXT
);
```

---

## 七、导演模式指令生成规则

### 7.1 LLM 输出格式（Function Calling 或 JSON Mode）

调用 LLM 时让模型按以下 JSON 格式输出（用 DeepSeek 的 JSON Mode 或 system prompt 约束）：

```json
{
  "reply_text": "刚洗完澡好舒服啊，你找我干嘛~",
  "director_prompt": "场景：刚洗完热水澡，浑身放松，头发还湿着，心情很好。\n指导：语速适中偏慢，声音带着慵懒但明快，尾音上扬，气声较多，像在哼歌的节奏里说话。"
}
```

### 7.2 Director Prompt 拼接规则

App 端拿到 `director_prompt` 后，拼接成完整 user message 发给 TTS：

```
角色：{agent.persona.director_role_template}

{director_prompt}
```

### 7.3 TTS 请求构造

```kotlin
suspend fun TtsClient.synthesize(
    text: String,                  // reply_text
    directorPrompt: String,        // 拼接后的完整导演指令
    sampleAudio: ByteArray         // voice/sample.wav 内容
): ByteArray {
    val messages = listOf(
        Message(role = "user", content = directorPrompt),
        Message(role = "assistant", content = text)
    )
    val audio = AudioConfig(
        format = "wav",
        voice = sampleAudioBase64  // MiMo voiceclone 的样本传入方式
    )
    // 调 /v1/chat/completions
}
```

### 7.4 MiMo voiceclone 调用细节

根据文档：
- voiceclone 模型每次调用都需传音频样本
- 模型 ID：`mimo-v2.5-tts-voiceclone`
- 不返回 voice_id，每次都是「样本 + 文本 → 语音」
- 支持自然语言控制（导演模式放在 user message）

**注意**：voiceclone 是否支持唱歌模式？文档说 voiceclone 不支持唱歌。V1 不做唱歌。

---

## 八、用户引导流程（App 首次启动）

```
App 启动
   ↓
检查是否有 active agent_config
   ↓ 无
显示欢迎页："欢迎使用 XX Agent"
   ↓
"第一步：导入你的 Agent"
   ↓
[选择文件] → 用户用 SAF 选择 .agent.zip
   ↓
解压校验：必须有 agent.json + voice/sample.wav
   ↓
"第二步：配置 API Key"
   ↓
显示两个卡片：
  [LLM 配置]
    Base URL: https://api.deepseek.com/v1 （默认已填）
    Model:    deepseek-chat             （默认已填）
    API Key:  [________________]
    [如何获取 Key？] → 跳 DeepSeek 注册页
  
  [TTS 配置]
    Base URL: https://api.xiaomimimo.com/v1 （默认已填）
    Model:    mimo-v2.5-tts-voiceclone   （默认已填）
    API Key:  [________________]
    [如何获取 Key？] → 跳 MiMo 注册页

[测试连接] → 后端调一次最小 LLM + TTS 请求，验证 Key 有效
   ↓
"第三步：开启后台运行权限"
   ↓
检测 ROM 品牌 → 显示对应引导卡片
  [✓ 已开启自启动]
  [✓ 已关闭电池优化]
  [✓ 已锁定后台]
  [全部完成，下一步]
   ↓
"配置完成！"
   ↓
进入主聊天页
```

---

## 九、合规与安全

### 9.1 法律合规

- App 内置《用户协议》和《隐私政策》，明确说明：
  - 所有数据本地存储，不上云
  - 用户对自己的 Agent 内容负责
  - 不得用于冒充他人、诈骗等违法用途
- 音色克隆前强制弹窗："我承诺拥有该音频样本的使用权"
- App 启动后显示 AI 生成内容标识

### 9.2 内容安全

V1 不做内容审核（用户自用），但保留以下接口：
- LLM 输出可被关键词过滤（设置中可开启）
- 聊天记录本地加密（Room + SQLCipher，V1 可选）

### 9.3 安全护栏

- API Key 本地存储使用 EncryptedSharedPreferences
- Agent 配置包不含任何 Key 信息
- 网络请求强制 HTTPS
- 不上报任何用户数据

---

## 十、开发阶段与里程碑

### 10.1 阶段一：基础骨架（App + Admin）

**App 侧**：
- 项目依赖配置（Room / OkHttp / Retrofit / Coroutines / Serialization）
- 数据层搭建（数据库 + DAO + Entity）
- 配置导入流程（.agent.zip 解压 + 校验）
- 基础聊天 UI（不含状态机）
- API Key 配置页

**Admin 侧**：
- Go 项目脚手架（Gin + GORM + SQLite）
- Vue 项目脚手架（Vite + Element Plus + Pinia）
- Agent 基础 CRUD
- 文件上传（头像/音频）

**交付物**：能在 Web 上创建一个简单 Agent，导出 zip，App 能导入并展示配置。

### 10.2 阶段二：核心能力打通

**App 侧**：
- LLM 客户端（OpenAI 兼容）
- TTS 客户端（MiMo voiceclone）
- 单次对话链路（用户发消息 → LLM → TTS → 播放）
- 前台服务骨架

**Admin 侧**：
- 实时预览（调 LLM + TTS）
- 完整编辑器表单
- 导出完整 .agent.zip

**交付物**：能在 Web 上精细配置并预览 Agent，导出后 App 能聊天（无状态机）。

### 10.3 阶段三：状态机与拟人行为

**App 侧**：
- 状态机核心（StateScheduler + StateMachine）
- 各状态回复策略
- 待回复消息队列
- 洗澡/睡觉批量回复
- 无聊主动发起
- 通知系统
- 头像按状态切换
- 被杀恢复机制

**交付物**：完整拟人体验，能"像真人一样生活"。

### 10.4 阶段四：体验打磨

- 后台权限引导流程（分品牌）
- 启动向导 UI 打磨
- 聊天 UI 动效
- 语音播放优化（流式播放）
- 错误处理与重试
- 省电优化
- 测试与 Bug 修复

**交付物**：可发布 V1.0。

---

## 十一、技术风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 国产 ROM 杀后台 | 状态机失效 | 前台服务 + 引导白名单 + 被杀恢复 |
| voiceclone 调用慢 | 体验差 | 文字先回，语音异步生成 |
| MiMo 闲时免费结束 | 成本增加 | 支持自定义 TTS base_url，可切换其他厂商 |
| LLM 输出格式不稳定 | 解析失败 | 用 JSON Mode + 容错解析 + 重试 |
| 音频样本质量差 | 克隆效果差 | Admin 端提供质量检测 + 引导 |
| 状态切换被系统延迟 | 主动消息不准时 | 用 `setExactAndAllowWhileIdle` |
| Room 加密增加复杂度 | 开发成本 | V1 默认不加密，预留接口 |

---

## 十二、目录与路径

| 项目 | 路径 |
|---|---|
| App | `e:\my_projects\AndroidStudioProjects\ta` |
| Admin Web 前端 | `E:\my_projects\go\demo\ta-admin\web` |
| Admin 后端 | `E:\my_projects\go\demo\ta-admin\server` |
| 本规划文档 | `e:\my_projects\AndroidStudioProjects\ta\docs\PROJECT_PLAN.md` |

---

## 十三、版本记录

| 版本 | 日期 | 修改内容 |
|---|---|---|
| v1.0 | 2026-07-13 | 初版规划 |
| v1.1 | 2026-07-13 | 新增微信风格聊天 UI + 语音优先策略（3.8 节） |
| v1.2 | 2026-07-13 | 确认 ExoPlayer + 流式预留；TTS 失败降级文字；不自动播放 |
| v1.3 | 2026-07-13 | 包名改为 com.agent.ta；Admin 不调模型，配置包纯人格无 Key，完全通用 |
| v1.4 | 2026-07-13 | 预置默认 Agent 开箱即用；「我的」页面配置模型和称呼；记忆系统设计；Onboarding 对话流程 |
