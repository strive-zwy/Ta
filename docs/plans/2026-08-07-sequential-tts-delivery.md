# Sequential TTS Delivery Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 多条 Agent 回复按原顺序逐条合成 TTS 并在每条完成后立即显示，纯 Emoji 附着到文字消息。

**Architecture:** 用纯函数先将 `ReplyItem` 规范化为无独立纯 Emoji 的文字回复列表，再由 `ChatInteractor` 串行执行“校验、TTS、校验、入库”。已成功入库的消息保留，取消只阻止当前和后续消息。

**Tech Stack:** Kotlin、Coroutines、Room、JUnit。

---

### Task 1: Emoji 附着策略

**Files:**
- Create: `app/src/main/java/com/agent/ta/domain/ReplyDeliveryPolicy.kt`
- Create: `app/src/test/java/com/agent/ta/domain/ReplyDeliveryPolicyTest.kt`

1. 编写前置、中间、尾部和全 Emoji 的失败测试。
2. 运行目标测试并确认因策略不存在失败。
3. 实现最小纯函数 `attachPureEmoji`。
4. 重跑目标测试并确认通过。

### Task 2: 串行逐条持久化

**Files:**
- Modify: `app/src/main/java/com/agent/ta/domain/ChatInteractor.kt:1480-1560`

1. 在进入多消息模式前应用 Emoji 附着策略。
2. 删除预先收集全部 `audioResults` 的循环。
3. 在单个循环中依次合成当前条 TTS 并立即插入当前消息。
4. 每条写入前检查取消和 generation。
5. 记录最后一条成功消息，整批结束后只通知一次。

### Task 3: 回归验证

1. 运行策略目标测试。
2. 运行 `:app:testDebugUnitTest`。
3. 运行 `:app:lintDebug`。
4. 运行 `:app:assembleDebug`。
5. 运行 `git diff --check`。
