# LLM Model Test Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在模型配置页使用当前未保存配置发送 `hello` 并展示 LLM 回复与耗时。

**Architecture:** 在 `LlmClient` 增加显式配置诊断接口，直接调用现有 OpenAI 兼容 Retrofit API；Compose 页面只负责输入校验、异步状态和结果弹窗，不修改 Preferences 或聊天数据。

**Tech Stack:** Kotlin、Coroutines、Retrofit、Jetpack Compose、JUnit。

---

### Task 1: LLM 诊断模型和客户端接口

**Files:**
- Modify: `app/src/main/java/com/agent/ta/data/remote/LlmClient.kt`
- Test: `app/src/test/java/com/agent/ta/data/remote/LlmDiagnosisResultTest.kt`

1. 先编写诊断结果和输入清洗的失败测试。
2. 运行目标单元测试，确认因类型或方法不存在而失败。
3. 实现 `LlmDiagnosisResult` 和显式配置 `diagnose` 方法。
4. 运行目标测试并确认通过。

### Task 2: 模型配置页测试交互

**Files:**
- Modify: `app/src/main/java/com/agent/ta/ui/screens/profile/ModelConfigScreen.kt`

1. 增加 LLM 独立测试状态与结果状态。
2. 在 LLM 卡片底部增加“测试模型”按钮。
3. 调用显式诊断接口，禁止持久化临时配置。
4. 增加成功/失败结果弹窗，展示回复、耗时或错误。

### Task 3: 回归验证

1. 运行 `:app:testDebugUnitTest`。
2. 运行 `:app:lintDebug`。
3. 运行 `:app:assembleDebug`。
4. 安装 Debug APK，在模拟器验证按钮和弹窗。
5. 运行 `git diff --check`。
