# TTS Naturalness Optimization Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在保留 VoiceClone 克隆音色的前提下，让每条 Agent 语音更接近日常真人聊天，并消除错误拆分、提示冲突和音频格式错配。

**Architecture:** 将 TTS 前处理拆成可单元测试的纯 Kotlin 策略：消息只在真正过长时按完整句末拆分，导演提示按开关和声音风格分层生成，音频响应携带真实格式并按格式落盘。VoiceClone 继续作为首选模型，情绪样本缺失时稳定回退 neutral，不引入音频拼接、人工静音或更换预置音色。

**Tech Stack:** Kotlin, Android, kotlinx.serialization, OkHttp, JUnit 4, AndroidX Media3

---

### Task 1: 提取并测试长消息拆分策略

**Files:**
- Create: `app/src/main/java/com/agent/ta/domain/TtsTextPolicy.kt`
- Create: `app/src/test/java/com/agent/ta/domain/TtsTextPolicyTest.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/ChatInteractor.kt`

**Steps:**
1. 为短消息、多句短消息、超过阈值的多句消息、包含波浪号和省略号的消息编写失败测试。
2. 运行 `./gradlew :app:testDebugUnitTest --tests "com.agent.ta.domain.TtsTextPolicyTest"`，确认测试先失败。
3. 实现纯 Kotlin 拆分策略：未超过长度阈值保持原 reply，超过阈值后仅按完整句末标点拆分，不按 `~`、`～`、逗号拆分。
4. 让 `ChatInteractor` 使用新策略，保留每条 reply 独立 TTS 的现有路径。
5. 重跑目标测试并确认通过。

### Task 2: 收敛并测试导演提示策略

**Files:**
- Create: `app/src/main/java/com/agent/ta/data/remote/TtsPromptPolicy.kt`
- Create: `app/src/test/java/com/agent/ta/data/remote/TtsPromptPolicyTest.kt`
- Modify: `app/src/main/java/com/agent/ta/data/remote/TtsClient.kt`

**Steps:**
1. 为导演模式关闭、导演模式开启、声音风格关闭、声音风格开启和重复指令编写失败测试。
2. 运行 `./gradlew :app:testDebugUnitTest --tests "com.agent.ta.data.remote.TtsPromptPolicyTest"`，确认测试先失败。
3. 实现分层提示：始终只有一条简短自然聊天基线；导演模式关闭时忽略 LLM `directorPrompt`；开启时只保留规范化后的单句语气描述；声音风格开启时追加一个紧凑的声学描述。
4. 删除八条堆叠式自然度规则，避免“连读、吞音、拖音、换气”等指令互相竞争。
5. 让 `TtsClient` 统一调用新策略，并重跑目标测试。

### Task 3: 修正 VoiceClone 输出格式链路

**Files:**
- Modify: `app/src/main/java/com/agent/ta/data/remote/dto/TtsDto.kt`
- Modify: `app/src/main/java/com/agent/ta/data/remote/TtsClient.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/ChatInteractor.kt`
- Create: `app/src/test/java/com/agent/ta/data/remote/TtsAudioFormatTest.kt`

**Steps:**
1. 为 WAV、MP3、未知格式和文件头识别编写失败测试。
2. 将 TTS 合成返回值从裸 `ByteArray` 改为包含 `bytes` 与 `format` 的结果对象。
3. VoiceClone 请求中保留样本 Data URL 的真实格式，但输出格式固定请求 WAV。
4. 响应优先采用 `audio.format`，缺失时根据 RIFF/ID3/MP3 帧头识别；无法识别时拒绝以伪 WAV 落盘。
5. `ChatInteractor` 按真实格式创建缓存文件，并继续交给 ExoPlayer 播放。
6. 运行音频格式目标测试并确认通过。

### Task 4: 稳定情绪样本选择

**Files:**
- Modify: `app/src/main/java/com/agent/ta/data/model/AgentConfig.kt`
- Create: `app/src/test/java/com/agent/ta/data/model/VoiceConfigTest.kt`
- Modify: `app/src/main/java/com/agent/ta/data/remote/TtsClient.kt`

**Steps:**
1. 为 neutral、happy、calm 的独立样本和 neutral 回退编写测试。
2. 保持“明确配置的情绪样本优先，否则 neutral”规则，不根据情绪标签切换到不存在或无效的文件。
3. 在运行时验证样本文件存在；无效情绪样本回退 neutral，neutral 也无效时停止 VoiceClone 而不是发送错误样本。
4. 运行 `VoiceConfigTest` 并确认通过。

### Task 5: 回归验证

**Files:**
- Modify if needed: `app/src/main/java/com/agent/ta/data/remote/TtsClient.kt`
- Modify if needed: `app/src/main/java/com/agent/ta/domain/ChatInteractor.kt`

**Steps:**
1. 运行 `./gradlew :app:testDebugUnitTest`，确认全部单元测试通过。
2. 运行 `./gradlew :app:assembleDebug`，确认 Debug APK 构建成功。
3. 使用固定文本验证单 reply 不拆分：`好的，收到，这会儿正走着呢，到家赶紧冲个热水澡，放心～被人惦记着还挺暖的。`
4. 使用固定文本验证长消息只在超过阈值后按句末拆分。
5. 使用同一 neutral VoiceClone 样本试听 neutral/happy/calm，确认无格式杂音、无音色突变，且导演模式开关行为符合设置。
