# 第二批数据可靠性改造设计

## 目标

修复异步回复跨 Agent 回写、导入期间旧任务继续运行、承诺重复领取或提前确认、积压消息提前确认以及 AgentEngine 初始化失败后无法恢复的问题。

## Agent 操作上下文

新增不可变 `AgentOperationContext`，包含 `agentId`、`generation` 和 Agent 配置快照。用户回复、积压回复、主动发起、承诺提醒及回复后副作用共享同一上下文。

Agent 配置更新或替换时提升全局 generation。异步任务在远程调用返回后以及任何数据库写入前检查上下文是否仍有效。失效任务停止写入，不重新读取 active Agent。

关系、情绪、里程碑和头像更新接口改为显式接收目标 `agentId`。主动消息入口也必须显式接收目标 Agent。

## 导入切换屏障

Agent 导入使用进程级 `Mutex` 串行化。ZIP 解析和校验在锁外完成，数据库切换在锁内按以下顺序执行：

1. 提升 generation，使旧任务失效。
2. 取消并等待旧回复与主动消息任务结束。
3. 取消旧 Agent 的承诺 Alarm 和延迟主动任务。
4. 事务更新同名 Agent 或替换不同名 Agent。
5. 发布 active Agent 和配置缓存。
6. 统一恢复 AgentEngine。

同名导入保留聊天和记忆。未完成回复恢复为 pending，并使用新配置重新生成。不同名导入按既定产品规则清空旧 Agent 数据，未完成任务不迁移。

## 承诺状态机

承诺状态采用：

```text
pending → claimed → delivered → completed
claimed → pending
claimed → failed
pending/claimed/delivered → cancelled
```

Alarm、Heartbeat 和启动恢复使用 DAO 条件更新原子领取承诺。只有从 `pending` 成功更新为 `claimed` 的执行者可以生成提醒。

提醒消息成功落库后更新为 `delivered`。失败时增加 `retryCount`，计算 `nextRetryAt` 并恢复 `pending`。最多自动重试三次，之后进入 `failed`。多条到期承诺串行处理，不互相取消。

主动提醒显式使用承诺所属 `agentId`。PendingIntent 身份由 `agentId` 和 `commitmentId` 共同确定，删除 Agent 前取消其全部待触发 Alarm。

## 积压消息状态机

积压消息采用：

```text
pending → processing → replied
processing → pending
```

领取时为批次生成 `batchId` 和 `claimedAt`，查询与状态更新在事务中完成。合并用 hidden 消息绑定该批次。回复成功落库后才将原消息确认成 `replied`；取消、异常或上下文失效时恢复 pending。

启动和导入恢复会把超时 processing 释放为 pending。并发处理器只能领取一次，不生成重复补充消息。

## AgentEngine 生命周期

使用 `STOPPED`、`STARTING`、`RUNNING`、`FAILED` 表示生命周期。`start()`、导入恢复和配置 reload 共享恢复互斥锁。

恢复先停止旧 producer、scheduler 和周期任务，再加载并发布目标 Agent，之后恢复 pending、承诺、Observer、Heartbeat 和情绪 ticker。初始化异常时清理已启动组件并进入 `FAILED`，后续 `start()` 可以重试。

本批不改变前台服务类型，不引入 WorkManager，也不处理第三批后台架构调整。

## 数据库

`CommitmentEntity` 增加 `retryCount`、`nextRetryAt` 和领取时间字段。`ChatMessageEntity` 增加 `batchId` 和 `claimedAt`。数据库版本升级并继续遵守项目现有 `fallbackToDestructiveMigration(true)` 约束。

## 验证

- 旧 generation 的回复不能写入新 Agent。
- 同名导入取消旧回复并恢复为 pending。
- Alarm、Heartbeat 和启动恢复并发时只能领取一次承诺。
- 多条到期承诺全部处理且互不取消。
- 承诺失败最多自动重试三次，成功落库后才标记 delivered。
- pending 取消或异常后恢复，超时 processing 可在启动时释放。
- AgentEngine 初始化失败后可再次启动。
- stop/start 后不存在重复周期任务。
- JVM 单元测试、Android Lint 和 Debug 构建全部通过。
