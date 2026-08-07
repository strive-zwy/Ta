# First Batch Release Security Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在保持现有模型配置 UI 和运行时 API 不变的情况下，修复密钥明文存储、Release 签名回退、系统备份、隐私日志和 Agent ZIP 导入边界。

**Architecture:** 将模型元数据与 Secret 分离，`UserPreferences` 对外继续返回完整 `ModelEntry`，内部仅把元数据写入普通 Preferences，并按模型 ID 从加密区装配 Key。将 ZIP 边界提取为纯 Kotlin 策略便于单元测试，Release 签名和备份使用构建与 Manifest 硬门禁，敏感日志统一通过发布态静默的包装器。

**Tech Stack:** Kotlin, Android SharedPreferences, AndroidX Security Crypto, Gradle Kotlin DSL, GitHub Actions, JUnit 4

---

### Task 1: LLM API Key 分离与迁移

**Files:**
- Create: `app/src/main/java/com/agent/ta/data/prefs/ModelSecretPolicy.kt`
- Create: `app/src/test/java/com/agent/ta/data/prefs/ModelSecretPolicyTest.kt`
- Modify: `app/src/main/java/com/agent/ta/data/prefs/UserPreferences.kt`

**Steps:**
1. 测试运行时模型能由普通元数据和加密 Key 组装。
2. 测试持久化普通 JSON 时所有 `apiKey` 为空。
3. 测试旧模型列表迁移后生成按 ID 保存的 Key 映射。
4. 运行目标测试，确认因策略缺失失败。
5. 实现纯 Kotlin 密钥拆分、组装和迁移策略。
6. 接入 `UserPreferences`：写入先保存 Key，再保存脱敏 JSON；删除模型时删除加密 Key。
7. 重跑测试并检查旧版迁移兼容。

### Task 2: 关闭系统备份

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/xml/backup_rules.xml`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`

**Steps:**
1. 设置 `allowBackup=false`。
2. 将云备份和设备迁移规则设为全排除，形成纵深防御。
3. 运行 `lintDebug` 验证 Manifest 和资源合法。

### Task 3: Release 签名硬门禁

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/release.yml`

**Steps:**
1. 删除 Release 到 debug signing 的回退。
2. 仅在请求 Release task 时检查四项签名配置并失败退出，避免影响 Debug 开发。
3. CI 在构建前验证 Secrets 完整，在构建后用 `apksigner` 校验签名。
4. 运行 Debug 构建，确认本地无 keystore 时仍可开发。
5. 运行无 keystore 的 Release 配置验证，确认明确失败。

### Task 4: 隐私日志收敛

**Files:**
- Create: `app/src/main/java/com/agent/ta/util/PrivacyLogger.kt`
- Modify: `app/src/main/java/com/agent/ta/data/remote/LlmClient.kt`
- Modify: `app/src/main/java/com/agent/ta/data/remote/TtsClient.kt`
- Modify: `app/src/main/java/com/agent/ta/state/memory/MemoryStore.kt`
- Modify: `app/src/main/java/com/agent/ta/service/CommitmentTriggerReceiver.kt`

**Steps:**
1. 实现 Debug 可用、Release 静默的敏感日志入口。
2. 将聊天、记忆、承诺、LLM/TTS 原文日志替换为长度、类型和 ID 等非敏感信息。
3. 保留异常堆栈但移除包含正文的错误消息。
4. 用内容搜索确认高风险日志不再存在。

### Task 5: Agent ZIP 导入边界

**Files:**
- Create: `app/src/main/java/com/agent/ta/util/AgentImportPolicy.kt`
- Create: `app/src/test/java/com/agent/ta/util/AgentImportPolicyTest.kt`
- Modify: `app/src/main/java/com/agent/ta/util/AgentConfigImporter.kt`

**Steps:**
1. 测试允许的路径、拒绝绝对路径和 `..` 路径。
2. 测试条目数、单文件大小和总解压大小边界。
3. 测试头像与 WAV/MP3 文件头识别。
4. 运行目标测试确认失败。
5. 实现纯 Kotlin 导入策略。
6. 解压过程使用计数复制，超过边界立即失败；所有路径做 canonical containment。
7. `agent.json`、头像和语音使用固定白名单；导入失败清理目录。
8. 重跑目标测试。

### Task 6: 完整验证

**Files:**
- Modify if needed: affected files above

**Steps:**
1. 运行 `./gradlew :app:testDebugUnitTest`。
2. 运行 `./gradlew :app:lintDebug`。
3. 运行 `./gradlew :app:assembleDebug`。
4. 运行 `git diff --check`。
5. 搜索普通 Preferences 序列化路径，确认不会写入非空 `apiKey`。
6. 搜索 Release 配置，确认不存在 debug signing fallback。
7. 搜索敏感日志正文，确认已替换或仅限安全包装。
