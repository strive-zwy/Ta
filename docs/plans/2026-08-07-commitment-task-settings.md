# Commitment Task Settings Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将承诺提醒开关移入定时任务页面，并在切换时同步 Alarm 调度。

**Architecture:** 设置页只负责导航；定时任务页读取并修改现有 Preferences。用纯策略筛选需要恢复调度的 pending 任务，页面协程负责 DAO 查询和 Scheduler 调用。

**Tech Stack:** Kotlin、Jetpack Compose、Room、AlarmManager、JUnit。

---

### Task 1: 恢复调度策略

**Files:**
- Create: `app/src/main/java/com/agent/ta/service/CommitmentSchedulePolicy.kt`
- Create: `app/src/test/java/com/agent/ta/service/CommitmentSchedulePolicyTest.kt`

1. 编写仅选择未来 pending 任务的失败测试。
2. 运行目标测试确认失败。
3. 实现纯筛选策略。
4. 重跑目标测试确认通过。

### Task 2: 设置页单一入口

**Files:**
- Modify: `app/src/main/java/com/agent/ta/ui/screens/profile/ProfileScreen.kt`

1. 将定时任务移出开发者模式条件。
2. 删除独立承诺提醒行和页面状态。
3. 更新开发者模式说明。

### Task 3: 列表页提醒设置

**Files:**
- Modify: `app/src/main/java/com/agent/ta/ui/screens/profile/CommitmentScreen.kt`

1. 顶部加入开关和动态说明。
2. 关闭时取消全部 pending Alarm。
3. 开启时恢复未来 pending Alarm。
4. 保持列表在两种状态下均可查看。

### Task 4: 验证

1. 运行目标测试和完整单元测试。
2. 运行 `:app:lintDebug` 与 `:app:assembleDebug`。
3. 安装 APK 并检查设置页和定时任务页 UI。
4. 运行 `git diff --check`。
