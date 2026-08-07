# Second Batch Data Reliability Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 通过 Agent 上下文代际、原子任务领取和可恢复状态机，阻止跨 Agent 回写并保证承诺和积压消息不会重复或丢失。

**Architecture:** 新增应用级 Agent 运行上下文管理器，为异步链路提供不可变 `agentId/generation/config`。承诺和 pending 消息由 Room 条件更新领取，导入与 Engine 恢复通过 Mutex 串行化，失败路径恢复为可重试状态。

**Tech Stack:** Kotlin Coroutines, Room, Android AlarmManager, JUnit 4, AndroidX Test

---

### Task 1: Agent 上下文代际

**Files:**
- Create: `app/src/main/java/com/agent/ta/domain/AgentOperationContext.kt`
- Create: `app/src/main/java/com/agent/ta/domain/AgentGenerationRegistry.kt`
- Create: `app/src/test/java/com/agent/ta/domain/AgentGenerationRegistryTest.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/ChatInteractor.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/RelationshipService.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/EmotionalService.kt`

**Steps:**
1. 为 generation 创建失效、当前性检查和并发提升测试。
2. 实现线程安全 registry 和不可变上下文。
3. 在 ChatInteractor 开始时捕获上下文，在远程调用后和写入前验证。
4. 关系、情绪、里程碑和头像更新显式传入 agentId。
5. 主动消息入口显式接收 Agent 上下文。
6. 运行目标测试和现有 ChatInteractor 测试。

### Task 2: 承诺原子领取和重试

**Files:**
- Modify: `app/src/main/java/com/agent/ta/data/local/entity/CommitmentEntity.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/dao/CommitmentDao.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/AppDatabase.kt`
- Create: `app/src/main/java/com/agent/ta/domain/CommitmentDeliveryCoordinator.kt`
- Modify: `app/src/main/java/com/agent/ta/service/CommitmentTriggerReceiver.kt`
- Modify: `app/src/main/java/com/agent/ta/service/CommitmentScheduler.kt`
- Modify: `app/src/main/java/com/agent/ta/service/AgentEngine.kt`
- Test: `app/src/androidTest/java/com/agent/ta/domain/CommitmentClaimRaceTest.kt`

**Steps:**
1. 写三路并发只领取一次和重试次数测试。
2. 增加 claimedAt、retryCount、nextRetryAt 字段并升级数据库版本。
3. DAO 增加 `claimPending()`、`markDelivered()` 和 `releaseForRetry()` 条件更新。
4. Coordinator 串行处理到期承诺，成功消息落库后标记 delivered。
5. 失败按退避恢复 pending，第三次失败进入 failed。
6. Receiver、Heartbeat 和启动恢复统一调用 Coordinator。
7. PendingIntent requestCode 混入 agentId，取消接口显式接收两个 ID。
8. 运行仪器测试和相关单元测试。

### Task 3: Pending 批次领取

**Files:**
- Modify: `app/src/main/java/com/agent/ta/data/local/entity/ChatMessageEntity.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/dao/ChatMessageDao.kt`
- Modify: `app/src/main/java/com/agent/ta/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/ChatInteractor.kt`
- Test: `app/src/androidTest/java/com/agent/ta/domain/PendingReplyRecoveryTest.kt`

**Steps:**
1. 写并发领取一次、取消恢复和超时释放测试。
2. 增加 batchId 和 claimedAt 字段。
3. 在 Room 事务中查询并领取 pending。
4. 生成成功后确认 replied，取消或异常恢复 pending。
5. hidden 合并消息绑定 batchId 并防止重复。
6. 启动时释放超时 processing。
7. 运行目标仪器测试。

### Task 4: 导入切换屏障

**Files:**
- Modify: `app/src/main/java/com/agent/ta/domain/AgentImportManager.kt`
- Modify: `app/src/main/java/com/agent/ta/domain/ChatInteractor.kt`
- Modify: `app/src/main/java/com/agent/ta/service/CommitmentScheduler.kt`
- Modify: `app/src/main/java/com/agent/ta/service/AgentEngine.kt`
- Test: `app/src/androidTest/java/com/agent/ta/domain/AgentImportCancellationTest.kt`

**Steps:**
1. 写不同名不回写和同名恢复 pending 测试。
2. 导入增加进程级 Mutex。
3. 更新数据库前提升 generation 并 cancelAndJoin 旧任务。
4. 取消旧 Agent 的承诺 Alarm 和延迟主动任务。
5. 同名导入恢复未完成批次，不同名导入清除旧上下文。
6. 刷新缓存后调用统一 Engine 恢复。
7. 运行导入和 Agent 隔离测试。

### Task 5: AgentEngine 生命周期恢复

**Files:**
- Modify: `app/src/main/java/com/agent/ta/service/AgentEngine.kt`
- Test: `app/src/test/java/com/agent/ta/service/AgentEngineLifecyclePolicyTest.kt`
- Test: `app/src/androidTest/java/com/agent/ta/service/AgentEngineRecoveryTest.kt`

**Steps:**
1. 写启动失败可重试和重复 start 不重复启动测试。
2. 使用生命周期枚举和恢复 Mutex 替换 isStarted。
3. start/reload 走同一恢复框架。
4. 异常时停止已启动组件并进入 FAILED。
5. 恢复 pending、承诺、Observer、Heartbeat 和 ticker。
6. 运行恢复测试。

### Task 6: 完整验证

**Steps:**
1. 运行 `./gradlew :app:testDebugUnitTest`。
2. 运行 `./gradlew :app:lintDebug`。
3. 运行 `./gradlew :app:assembleDebugAndroidTest`。
4. 在可用模拟器上运行新增与相关 androidTest。
5. 运行 `./gradlew :app:assembleDebug`。
6. 运行 `git diff --check`。
7. 检查所有主动消息入口都显式携带 agentId 或 AgentOperationContext。
