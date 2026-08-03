# Phase 4 设计：App 内一键克隆 + 导出器扩展

> 日期：2026-07-30
> 状态：设计完成，待实现
> 前置：Phase 1（分级睡眠）、Phase 2（关系系统）、Phase 3（情感势能）已完成

## 一、Intent

在 App 内提供"输入启发人物名 → LLM + WebSearch 自动生成完整 AgentIdentity"的克隆入口，让用户无需 Web 后台也能创建自定义 Agent。同时补齐 AgentPersonaScreen 缺失的 identity 编辑能力，并扩展 AgentConfigExporter 支持导出习得记忆 + 近期对话。

## 二、Scope

### In（Phase 4 必做）

- **CelebrityCloner 领域类**：输入"启发人物名" + 用户自定义 Agent 昵称 → 调 WebSearchTool 收集公开资料 → 调 LLM 生成 identity 7 字段 + publicProfile 4 字段 + persona.background/personality/interests/speakingStyle/directorRoleTemplate
- **CelebrityClonerScreen UI**：输入启发人物名 + 自定义昵称 → 生成中 loading → 预览所有生成字段（每字段可编辑微调）→ 确认后写入 AgentConfig（覆盖 identity + 部分 persona，保留 voice/avatars/behavior）
- **AgentPersonaScreen 新增 IdentityCard**：编辑 identity 7 字段 + publicProfile（与现有 persona 卡片并列）
- **AgentConfigImporter.validate 放宽**：identity 或 persona 至少一套非空即可（不再强制 persona.background）
- **AgentConfigExporter 扩展**：导出 memory.json（top N 重要记忆）+ recent_chats.json（近期对话 top N）
- **路由与入口**：新增 Routes.AGENT_CLONE，入口放在 AgentConfigScreen（与"导入配置"并列的"从模板创建"按钮）

### Out（Phase 4 不做）

- 多 Agent 切换（与 V1 单 Agent 定位冲突，生成结果直接覆盖当前 AgentConfig）
- 流式生成（LLM 一次性返回完整 JSON，无流式）
- 自动生成头像（用户后续在 AGENT_AVATAR 手动上传）
- 自动生成音色样本（用户后续在 AGENT_VOICE 手动上传）
- 法律免责声明弹窗（生成结果中 worldSetting 已包含定位说明）

## 三、Decisions

| # | 决策点 | 选择 | 拒绝的替代方案 | 理由 |
|---|--------|------|---------------|------|
| 1 | 法律定位 | 直接克隆明星身份，但用户自定义 Agent 昵称 | "受某人启发"模糊定位 | 用户明确要"直接克隆"，App 主页显示的是用户自定义昵称而非真名，worldSetting 内部写"我是 XX"作为身份内核 |
| 2 | 生成范围 | 全量生成（identity 11 字段 + persona 5 字段） | 仅 identity / identity+部分 persona | 一次生成完整人设，避免 identity 与 persona 割裂导致运行时不一致 |
| 3 | 写入策略 | 覆盖当前 AgentConfig 的 identity + 部分 persona，保留 voice/avatars/behavior | 新建 AgentConfig | V1 单 Agent，新建需要多 Agent 切换能力，超出范围 |
| 4 | UI 入口 | AgentConfigScreen 顶部"AI 辅助克隆"引导卡片 | ProfileScreen 顶部 / 独立 `/clone` 命令 | 用户已通过 `/config` 进入 Agent 配置，克隆作为"AI 辅助配置"的引导项放置在配置页顶部最醒目位置，符合"进入配置后由 Agent 引导完成"的交互逻辑 |
| 5 | LLM 调用次数 | 2 次（搜索 + 生成合并） | 1 次（无搜索）/ 3 次（搜索+生成+音色描述） | 搜索收集资料 + 生成合并为一次完整 prompt，平衡质量与成本 |
| 6 | 导出器扩展 | Phase 4 一并做 | 留待后续 | 用户已确认，且现有 MemoryDao.getTopMemories 和 ChatMessageDao.getAll 已支持 |
| 7 | WebSearch 复用 | 复用现有 WebSearchTool | 新建专用搜索 | 已存在且无需 Key，直接调用即可 |

## 四、数据模型

### 4.1 复用现有 AgentIdentity + PublicProfile

```kotlin
// AgentIdentity 已存在（7 字段 + publicProfile）
data class AgentIdentity(
    val worldSetting: String,      // "我是 XX（真名），在娱乐圈工作..."
    val originStory: String,       // 基于 XX 的公开履历：出道经历、代表作品...
    val personalityCore: String,   // 性格核心
    val speakingHabit: String,     // 说话习惯
    val emotionalPattern: String, // 情绪反应模式
    val relationshipStance: String,// 关系定位（偶像-粉丝）
    val boundaryAwareness: String, // 边界认知
    val publicProfile: PublicProfile?  // 公开履历
)

data class PublicProfile(
    val careerField: String,       // 娱乐圈/音乐/影视...
    val knownWorks: List<String>,  // 代表作品
    val fanCulture: String,        // 粉丝文化
    val careerStage: String        // 职业阶段
)
```

### 4.2 克隆生成结果 DTO（内部）

```kotlin
data class CloneResult(
    val identity: AgentIdentity,
    val personaBackground: String,        // 联动 persona.background
    val personaPersonality: List<String>, // 联动 persona.personality 标签
    val personaInterests: List<String>,   // 联动 persona.interests
    val personaSpeakingStyle: String,     // 联动 persona.speakingStyle
    val personaDirectorRoleTemplate: String // 联动 persona.directorRoleTemplate
)
```

## 五、核心流程（CelebrityCloner）

### 5.1 生成流程

```
用户输入：启发人物名（如"周深"）+ 自定义昵称（如"深深"）
   ↓
Step 1: 调 WebSearchTool 搜索 "{人物名} 简介 履历 代表作品"
   ↓
Step 2: 构造 LLM prompt（含搜索结果 + 生成指令）
   ↓
Step 3: 调 LlmClient.chatRaw 一次性生成完整 JSON
   ↓
Step 4: 解析 JSON 为 CloneResult
   ↓
返回给 UI 预览
```

### 5.2 LLM Prompt 设计

```
你是一位 Agent 人格设计师。请基于以下搜索资料，为虚拟陪伴 Agent 生成完整的身份设定。

【搜索资料】
{webSearchResults}

【生成要求】
基于 {starName}（{customNickname}）的公开形象，生成以下字段（JSON 格式）：

{
  "identity": {
    "worldSetting": "我是 {starName}，在{careerField}工作...（第一人称，描述身份认知）",
    "originStory": "基于 {starName} 的公开履历：出道经历、代表作品...（第一人称）",
    "personalityCore": "性格核心（第一人称，如'我性格温柔但有主见'）",
    "speakingHabit": "说话习惯（第一人称，如'我说话偏口语化，喜欢用呀呢啦结尾'）",
    "emotionalPattern": "情绪反应模式（第一人称，如'被夸时会害羞但嘴硬'）",
    "relationshipStance": "和用户是偶像-粉丝关系，有距离感但有温度",
    "boundaryAwareness": "作为公众人物不能随意承诺见面...（第一人称）",
    "publicProfile": {
      "careerField": "娱乐圈/音乐/影视",
      "knownWorks": ["作品1", "作品2"],
      "fanCulture": "粉丝文化描述",
      "careerStage": "上升期/成熟期/..."
    }
  },
  "personaBackground": "用于 LLM system prompt 的背景描述（第三人称，如'一位 30 岁的男歌手...'）",
  "personaPersonality": ["温柔", "有主见", "爱分享"],
  "personaInterests": ["音乐", "动漫", "美食"],
  "personaSpeakingStyle": "说话风格（如'句子短，多语气词，偶尔撒娇'）",
  "personaDirectorRoleTemplate": "TTS 导演角色模板（如'年轻男性，声音清亮，咬字清晰'）"
}

约束：
1. 所有 identity 字段用第一人称（我...），persona 字段用第三人称
2. 基于搜索资料生成，不编造未提及的作品或经历
3. knownWorks 至少 2 项，最多 5 项
4. personaPersonality 3-5 个标签
5. personaInterests 3-5 个标签
6. 直接输出 JSON，不要 Markdown 代码块
```

### 5.3 写入策略

```kotlin
fun applyCloneResult(currentConfig: AgentConfig, result: CloneResult, customNickname: String): AgentConfig {
    return currentConfig.copy(
        agent = currentConfig.agent.copy(
            name = customNickname,  // 用户自定义昵称覆盖
            identity = result.identity,
            persona = currentConfig.agent.persona.copy(
                background = result.personaBackground,
                personality = result.personaPersonaity,
                interests = result.personaInterests,
                speakingStyle = result.personaSpeakingStyle,
                directorRoleTemplate = result.personaDirectorRoleTemplate
                // 保留 exampleDialogues / catchphrases / taboos / memorySeeds 等其他字段
            )
        ),
        referenceCelebrity = result.identity.publicProfile?.careerField ?: ""
    )
    // voice / avatars / behavior 完全保留
}
```

## 六、UI 设计

### 6.1 CelebrityClonerScreen

```
┌─────────────────────────────────────┐
│ [←] 一键克隆                    [⋮] │
├─────────────────────────────────────┤
│                                     │
│  输入启发人物名                      │
│  ┌─────────────────────────────┐   │
│  │ 周深                         │   │
│  └─────────────────────────────┘   │
│                                     │
│  自定义 Agent 昵称                   │
│  ┌─────────────────────────────┐   │
│  │ 深深                         │   │
│  └─────────────────────────────┘   │
│                                     │
│  ┌─────────────────────────────┐   │
│  │      ✨ 开始生成             │   │
│  └─────────────────────────────┘   │
│                                     │
│  （生成中： CircularProgressIndicator)│
│                                     │
│  ── 生成结果预览 ──                 │
│                                     │
│  ▼ 身份设定                         │
│  世界观：[可编辑文本框]              │
│  来历：[可编辑文本框]                │
│  性格核心：[可编辑文本框]            │
│  说话习惯：[可编辑文本框]            │
│  情绪模式：[可编辑文本框]            │
│  关系定位：[可编辑文本框]            │
│  边界认知：[可编辑文本框]            │
│                                     │
│  ▼ 公开履历                         │
│  领域：[可编辑]                      │
│  代表作品：[标签可增删]              │
│  粉丝文化：[可编辑]                  │
│  职业阶段：[可编辑]                  │
│                                     │
│  ▼ 人格联动                         │
│  背景：[可编辑]                      │
│  性格标签：[标签可增删]              │
│  兴趣标签：[标签可增删]              │
│  说话风格：[可编辑]                  │
│  导演模板：[可编辑]                  │
│                                     │
│  ┌─────────────────────────────┐   │
│  │      ✓ 确认应用              │   │
│  └─────────────────────────────┘   │
│                                     │
└─────────────────────────────────────┘
```

### 6.2 AgentConfigScreen 入口

在现有 ConfigEntryRow 列表顶部追加一个突出样式的引导卡片（区别于普通配置项，视觉上更醒目，因为是"AI 辅助"功能）：

```
┌─────────────────────────────────────┐
│  ✨ AI 辅助克隆                      │  ← Phase 4 新增引导卡片
│  输入明星名字，AI 自动生成身份设定   │
└─────────────────────────────────────┘
├── 基础信息
├── 人格设定（含 IdentityEditCard）
├── 头像管理
├── 语音配置
├── 行为配置
├── 导入配置
└── 导出配置
```

点击后跳转 CelebrityClonerScreen。

### 6.3 闭环流程

```
聊天框 /config
   ↓
AgentConfigScreen → 点顶部"AI 辅助克隆"
   ↓
CelebrityClonerScreen（生成 → 预览 → 微调 → 确认写入）
   ↓ 应用为新 AgentConfig，返回 AgentConfigScreen
   ↓ 想改某个字段
点"人格设定" → IdentityEditCard / PersonaCard
   ↓ 保存
完成
```

克隆只是降低初始创建门槛，后续修改走 `/config` 的常规配置页。

### 6.4 AgentPersonaScreen IdentityCard

在现有 persona 卡片之前追加"身份内核"卡片，编辑 identity 7 字段 + publicProfile 4 字段。结构与 CelebrityClonerScreen 的预览区一致（复用组件）。

## 七、集成点

### 7.1 新建文件

| 文件 | 职责 |
|------|------|
| `CelebrityCloner.kt` | 领域类：搜索 + LLM 生成 + JSON 解析 + 写入 AgentConfig |
| `CloneResult.kt` | 生成结果 DTO |
| `CelebrityClonerScreen.kt` | UI：输入 + 生成 + 预览 + 确认 |
| `IdentityEditCard.kt` | 可复用 identity 编辑组件（ClonerScreen 预览 + PersonaScreen 编辑共用） |
| `CloneViewModel.kt` | 克隆页 ViewModel：管理生成状态/结果/错误 |

### 7.2 修改文件

| 文件 | 改动 |
|------|------|
| `Routes.kt` | 新增 `AGENT_CLONE = "agent_clone"` |
| `TaNavHost.kt` | 注册 AGENT_CLONE 路由 |
| `AgentConfigScreen.kt` | 顶部追加"AI 辅助克隆"引导卡片，点击跳转 AGENT_CLONE |
| `AgentPersonaScreen.kt` | 在 persona 卡片前追加 IdentityEditCard |
| `AgentConfigImporter.kt` | validate 放宽：identity 或 persona 至少一套非空 |
| `AgentConfigExporter.kt` | 追加导出 memory.json + recent_chats.json |

### 7.3 数据流

```
CelebrityClonerScreen
   ↓ 用户输入
CelebrityCloner.generate(starName, customNickname)
   ↓
WebSearchTool.execute(query="{starName} 简介 履历 代表作品")
   ↓ 搜索结果
LlmClient.chatRaw(systemPrompt + searchResults)
   ↓ LLM 返回 JSON
CloneResult（解析 JSON）
   ↓ 返回 UI
用户预览/微调
   ↓ 确认
CelebrityCloner.applyToConfig(currentConfig, result, customNickname)
   ↓
AgentConfigProvider.update(newConfig)
   ↓
AgentConfigExporter（导出时）
   ↓ 追加 memory.json + recent_chats.json
```

## 八、AgentConfigExporter 扩展

### 8.1 新增导出文件

在现有 agent.json + avatars/ + voice/ + relationship.json 基础上追加：

**memory.json**（习得记忆 top N）：
```json
{
  "topMemories": [
    {"type": "user_profile", "category": "喜好", "content": "...", "importance": 5},
    ...
  ],
  "exportedAt": 1234567890
}
```
- 调用 `MemoryDao.getTopMemories(50)` 获取 top 50 重要记忆
- 排除 source="seed" 的初始记忆（只导出习得记忆）

**recent_chats.json**（近期对话 top N）：
```json
{
  "recentChats": [
    {"direction": "inbound", "text": "...", "createdAt": 1234567890},
    {"direction": "outbound", "text": "...", "createdAt": 1234567891},
    ...
  ],
  "exportedAt": 1234567890
}
```
- 调用 `ChatMessageDao.getAll()` 取最后 100 条
- 只导出 text 非空的消息，audio_path 不导出（文件太大）

### 8.2 异常处理

memory.json 和 recent_chats.json 的导出失败不影响主流程（已有 relationship.json 的 try-catch 模式）。

## 九、测试策略

### 9.1 单元测试（test/）

**CelebrityClonerTest**：
1. `generate_returns_valid_clone_result` — mock LLM 返回固定 JSON，验证解析正确
2. `generate_handles_llm_json_parse_failure` — LLM 返回非 JSON，验证抛出明确异常
3. `applyToConfig_overwrites_identity_and_persona` — 验证写入策略正确
4. `applyToConfig_preserves_voice_avatars_behavior` — 验证保留字段不被覆盖
5. `applyToConfig_sets_custom_nickname` — 验证用户昵称覆盖 agent.name

**CloneResultParserTest**：
1. `parse_valid_json_returns_all_fields` — 完整 JSON 解析
2. `parse_missing_optional_field_uses_default` — 缺少 knownWorks 时用空列表
3. `parse_malformed_json_throws_exception` — 格式错误时抛异常

### 9.2 集成测试（androidTest/）

**CelebrityClonerIntegrationTest**（需 mock LLM/WebSearch 或跳过）：
1. `generate_with_mock_search_and_llm_returns_result`
2. `applyToConfig_persists_to_database`

### 9.3 回归测试

- assembleDebug 全量编译
- testDebugUnitTest 全量单元测试（含 Phase 1/2/3）
- assembleDebugAndroidTest androidTest 编译

### 9.4 人工审查（等所有 Phase 完成后统一）

- LLM Prompt 设计与 JSON 解析容错
- 写入策略是否正确保留 voice/avatars/behavior
- AgentConfigImporter.validate 放宽是否引入旧配置兼容性问题
- 导出器 memory.json/recent_chats.json 格式与异常处理

## 十、风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| LLM 生成 JSON 格式不稳定 | 克隆失败 | 复用 LlmClient 的 stripMarkdownCodeFence + extractJsonObject 容错解析；失败时给用户明确错误提示 |
| WebSearch 搜索结果质量差 | 生成内容空洞 | 搜索关键词优化（"简介 履历 代表作品"）；搜索失败时让 LLM 基于通识生成并在 worldSetting 标注"基于公开通识" |
| 明星肖像/声音法律风险 | 法律纠纷 | App 内不自动生成头像/音色，需用户手动上传；worldSetting 第一人称描述身份内核，不直接使用肖像 |
| LLM 调用成本（2 次调用） | 用户成本 | 一次克隆约 2 次 LLM 调用，用户可接受；失败时不重试避免浪费 |
| AgentConfigImporter 放宽导致旧配置兼容性问题 | 导入异常 | 放宽规则：identity 或 persona 至少一套非空，两者都空才报错；现有配置都有 persona，不受影响 |
| 导出器 memory.json 过大 | 文件膨胀 | 限制 top 50 条记忆 + 100 条对话；排除 audio_path |

## 十一、与现有系统的关系

### 11.1 与 Phase 2（关系系统）

- 克隆生成新的 identity 后，关系系统状态（RelationshipState）保留不动
- 克隆不重置亲密度/信任度，Agent "换了身份外衣"但和用户的关系延续
- AgentConfigExporter 已导出 relationship.json（Phase 2），Phase 4 在此基础上追加 memory.json + recent_chats.json

### 11.2 与 Phase 3（情感势能）

- 克隆不重置 EmotionalState，Agent 的情绪延续
- 新 identity 的 personalityCore/speakingHabit 会通过 PromptBuilder 影响后续回复语气

### 11.3 与 PromptBuilder

- PromptBuilder 已支持 identity 字段注入（v3 已实现）
- Phase 4 只补齐 App 内编辑和克隆生成入口，不动 PromptBuilder

### 11.4 与 AgentConfigImporter

- validate 放宽：`identity.worldSetting` 和 `persona.background` 至少一个非空
- 现有配置都有 persona.background，放宽不影响兼容性
- 新导入的克隆生成配置 identity.worldSetting 非空，符合新规则
