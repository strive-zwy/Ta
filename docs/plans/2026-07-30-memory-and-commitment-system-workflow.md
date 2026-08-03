---
intent: 增强每日记忆机制（语义化总结+结构化每日状态+MemoryDao 查询补齐+去重补齐+历史清理），并引入承诺/约定系统（CommitmentEntity+LLM 识别+AlarmManager 触发+完成追踪+观察者+记忆联动+创建工具）
success_criteria: Agent 能记住昨天做了什么（语义化）、能记住和用户的承诺、能在承诺时间到点主动提醒用户、能追踪承诺完成状态、历史记忆有自动清理
risk_level: medium
auto_approve: false
---

## Steps

- [ ] **Step 1: 创建 MemoryDao 查询能力补齐**
action: 在 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\data\local\dao\MemoryDao.kt` 中新增 4 个查询方法：
  1. `getByCategory(category: String, limit: Int = 50): List<MemoryEntity>` — 按 category 查询
  2. `getByCategoryAndDateRange(category: String, startTs: Long, endTs: Long): List<MemoryEntity>` — 按 category + 时间范围查询
  3. `findOneByCategoryAndKeyword(category: String, keyword: String): MemoryEntity?` — 按 category + 关键词查询单条（用于去重检查）
  4. `countByCategoryAndKeyword(category: String, keyword: String): Int` — 按 category + 关键词计数
  所有方法使用 @Query 注解，SQL 中 content LIKE '%' || :keyword || '%' 模糊匹配。不改原有方法签名。
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 2: 创建 DailyStateEntity 数据模型**
action: 在 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\data\local\entity\` 目录新建 `DailyStateEntity.kt`，定义结构化每日状态记录：
  ```kotlin
  @Entity(tableName = "daily_state")
  data class DailyStateEntity(
      @PrimaryKey
      val date: String,                  // "yyyy-MM-dd"
      val sleepTime: String?,            // "01:15" - 昨晚睡觉时间（从最后一个 unavailable slot 的 start 提取）
      val wakeTime: String?,             // "07:30" - 今早起床时间
      val sleepDurationMin: Int?,         // 360 - 实际睡眠时长（分钟）
      val mood: Float?,                  // -1.0~1.0（昨日情绪）
      val fatigue: Float?,               // 0.0-1.0（昨日疲劳）
      val stress: Float?,                // 0.0-1.0（昨日压力）
      val energy: Float?,                // 0.0-1.0（昨日精力水平）
      val mainActivities: String,        // JSON 数组字符串 ["赶设计稿","和朋友吃饭"]
      val specialEvents: String,         // JSON 数组字符串 ["生日","约会"]
      val hadInteractionWithUser: Boolean, // 当天是否和用户互动
      val interactionCount: Int,          // 互动消息数
      val summary: String,               // 语义化总结（LLM 生成，100-200 字）
      val createdAt: Long = System.currentTimeMillis(),
      val updatedAt: Long = System.currentTimeMillis()
  )
  ```
  字段注释用中文。包含必要的 import（androidx.room.Entity、PrimaryKey）。
loop: false
verify:
  type: artifact
  path: app\src\main\java\com\agent\ta\data\local\entity
  assert:
    kind: matches-glob
    value: "DailyStateEntity.kt"

- [ ] **Step 3: 创建 DailyStateDao**
action: 在 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\data\local\dao\` 目录新建 `DailyStateDao.kt`，定义以下方法：
  1. `@Insert suspend fun insert(entity: DailyStateEntity): Long`
  2. `@Update suspend fun update(entity: DailyStateEntity)`
  3. `@Query("SELECT * FROM daily_state WHERE date = :date LIMIT 1") suspend fun getByDate(date: String): DailyStateEntity?`
  4. `@Query("SELECT * FROM daily_state WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC") suspend fun getRange(startDate: String, endDate: String): List<DailyStateEntity>`
  5. `@Query("SELECT * FROM daily_state ORDER BY date DESC LIMIT :limit") suspend fun getRecent(limit: Int = 7): List<DailyStateEntity>`
  6. `@Transaction suspend fun upsertPreservingCreatedAt(entity: DailyStateEntity)` — 先查后写，保留 createdAt
  7. `@Query("DELETE FROM daily_state WHERE date < :beforeDate") suspend fun deleteBefore(beforeDate: String): Int`
loop: false
verify:
  type: artifact
  path: app\src\main\java\com\agent\ta\data\local\dao
  assert:
    kind: matches-glob
    value: "DailyStateDao.kt"

- [ ] **Step 4: 注册 DailyStateEntity 到 TaDatabase + DB 迁移 10→11**
action: 修改两个文件：
  1. `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\data\local\TaDatabase.kt`：
     - 在 entities 数组添加 `DailyStateEntity::class`
     - version = 11
     - 添加 `abstract fun dailyStateDao(): DailyStateDao`
  2. `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\TaApplication.kt`：
     - 在 Migration(9, 10) 之后添加 Migration(10, 11)，执行 `CREATE TABLE IF NOT EXISTS daily_state (...)`
     - 字段顺序与 DailyStateEntity 一致，type TEXT、Integer/Boolean 用 NOT NULL DEFAULT
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 5: ServiceLocator 注入 DailyStateDao**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\di\ServiceLocator.kt`，新增：
  ```kotlin
  val dailyStateDao: DailyStateDao by lazy { database.dailyStateDao() }
  ```
  在合适位置（其他 DAO 注入附近）添加。确保 import DailyStateDao。
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 6: 创建 CommitmentEntity 数据模型**
action: 在 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\data\local\entity\` 目录新建 `CommitmentEntity.kt`：
  ```kotlin
  @Entity(tableName = "commitments")
  data class CommitmentEntity(
      @PrimaryKey(autoGenerate = true)
      val id: Long = 0,
      val type: String,           // appointment（双方约定）/ promise（Agent 承诺）/ reminder（提醒用户）
      val content: String,        // "一起看《星际穿越》电影"
      val participants: String,   // "agent,user" / "agent" / "user"
      val triggerAt: Long?,       // 精确触发时间戳（毫秒），null 表示无精确触发时间
      val deadline: Long?,        // 截止时间戳（毫秒），null 表示无截止
      val status: String,         // pending / triggered / completed / cancelled / expired
      val source: String,         // chat（LLM 被动提取）/ tool（工具主动创建）/ manual
      val relatedMessageId: Long?, // 关联的对话消息 ID（追溯"在哪句答应的"）
      val createdAt: Long = System.currentTimeMillis(),
      val updatedAt: Long = System.currentTimeMillis()
  )
  ```
  字段注释用中文。包含必要的 import。
loop: false
verify:
  type: artifact
  path: app\src\main\java\com\agent\ta\data\local\entity
  assert:
    kind: matches-glob
    value: "CommitmentEntity.kt"

- [ ] **Step 7: 创建 CommitmentDao**
action: 在 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\data\local\dao\` 目录新建 `CommitmentDao.kt`：
  1. `@Insert suspend fun insert(entity: CommitmentEntity): Long`
  2. `@Update suspend fun update(entity: CommitmentEntity)`
  3. `@Query("SELECT * FROM commitments WHERE id = :id LIMIT 1") suspend fun getById(id: Long): CommitmentEntity?`
  4. `@Query("SELECT * FROM commitments WHERE status = :status ORDER BY triggerAt ASC") suspend fun getByStatus(status: String): List<CommitmentEntity>`
  5. `@Query("SELECT * FROM commitments WHERE status = 'pending' AND triggerAt IS NOT NULL AND triggerAt <= :now ORDER BY triggerAt ASC") suspend fun getDueCommitments(now: Long): List<CommitmentEntity>`
  6. `@Query("SELECT * FROM commitments WHERE status IN ('pending','triggered') AND deadline IS NOT NULL AND deadline < :now") suspend fun getExpiredCommitments(now: Long): List<CommitmentEntity>`
  7. `@Query("SELECT * FROM commitments WHERE date(createdAt/1000, 'unixepoch') = :dateStr ORDER BY createdAt DESC") suspend fun getByDate(dateStr: String): List<CommitmentEntity>` — 注意 SQLite 用 strftime，改用 `WHERE createdAt >= :startTs AND createdAt < :endTs`
  8. `@Query("UPDATE commitments SET status = :status, updatedAt = :updatedAt WHERE id = :id") suspend fun updateStatus(id: Long, status: String, updatedAt: Long = System.currentTimeMillis())`
  9. `@Query("DELETE FROM commitments WHERE status IN ('completed','cancelled','expired') AND updatedAt < :beforeTs") suspend fun deleteOldCompleted(beforeTs: Long): Int`
loop: false
verify:
  type: artifact
  path: app\src\main\java\com\agent\ta\data\local\dao
  assert:
    kind: matches-glob
    value: "CommitmentDao.kt"

- [ ] **Step 8: 注册 CommitmentEntity 到 TaDatabase + DB 迁移 11→12**
action: 修改两个文件：
  1. `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\data\local\TaDatabase.kt`：
     - entities 数组添加 `CommitmentEntity::class`
     - version = 12
     - 添加 `abstract fun commitmentDao(): CommitmentDao`
  2. `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\TaApplication.kt`：
     - 在 Migration(10, 11) 之后添加 Migration(11, 12)，执行 `CREATE TABLE IF NOT EXISTS commitments (...)`
     - 字段顺序与 CommitmentEntity 一致
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 9: ServiceLocator 注入 CommitmentDao**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\di\ServiceLocator.kt`，新增：
  ```kotlin
  val commitmentDao: CommitmentDao by lazy { database.commitmentDao() }
  ```
  确保 import CommitmentDao。
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 10: 创建 CommitmentScheduler（AlarmManager 触发）**
action: 在 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\service\` 目录新建 `CommitmentScheduler.kt`：
  ```kotlin
  class CommitmentScheduler(private val context: Context) {
      private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
      
      fun scheduleCommitmentTrigger(commitment: CommitmentEntity) {
          val triggerAt = commitment.triggerAt ?: return
          if (triggerAt <= System.currentTimeMillis()) return
          val intent = Intent(context, CommitmentTriggerReceiver::class.java).apply {
              putExtra("commitment_id", commitment.id)
              action = "com.agent.ta.COMMITMENT_TRIGGER"
          }
          val pendingIntent = PendingIntent.getBroadcast(
              context, commitment.id.toInt(), intent,
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
          )
          // Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限，用 setExactAndAllowIdle
          // 失败时降级为 set（不精确但兜底）
          try {
              alarmManager.setExactAndAllowIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
          } catch (e: SecurityException) {
              alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
          }
      }
      
      fun cancelCommitmentTrigger(commitmentId: Long) {
          val intent = Intent(context, CommitmentTriggerReceiver::class.java).apply {
              action = "com.agent.ta.COMMITMENT_TRIGGER"
          }
          val pendingIntent = PendingIntent.getBroadcast(
              context, commitmentId.toInt(), intent,
              PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
          )
          pendingIntent?.let { alarmManager.cancel(it) }
      }
  }
  ```
  注意：Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限，先在 try-catch 中用 setExactAndAllowIdle，失败降级。
loop: false
verify:
  type: artifact
  path: app\src\main\java\com\agent\ta\service
  assert:
    kind: matches-glob
    value: "CommitmentScheduler.kt"

- [ ] **Step 11: 创建 CommitmentTriggerReceiver（BroadcastReceiver）**
action: 在 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\service\` 目录新建 `CommitmentTriggerReceiver.kt`：
  ```kotlin
  class CommitmentTriggerReceiver : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
          val commitmentId = intent.getLongExtra("commitment_id", -1L)
          if (commitmentId == -1L) return
          
          // 使用 goAsync 避免主线程阻塞
          val pendingResult = goAsync()
          CoroutineScope(Dispatchers.IO).launch {
              try {
                  val commitmentDao = ServiceLocator.commitmentDao
                  val commitment = commitmentDao.getById(commitmentId) ?: return@launch
                  if (commitment.status != "pending") return@launch
                  
                  // 1. 标记为 triggered
                  commitmentDao.updateStatus(commitment.id, "triggered")
                  
                  // 2. 构造 topicHint 并触发主动消息
                  val topicHint = when (commitment.type) {
                      "appointment" -> "到了和用户约定的时间：${commitment.content}。你可以说类似'时间到啦，你那边准备好了吗？'"
                      "promise" -> "你之前答应了用户：${commitment.content}。现在该去做了，可以告诉用户你开始做了"
                      "reminder" -> "你之前答应了提醒用户：${commitment.content}。现在该提醒用户了"
                      else -> "承诺时间到了：${commitment.content}"
                  }
                  
                  // 3. 启动 ChatInteractor 发起主动消息
                  val interactor = ChatInteractor(context)
                  interactor.agentInitiate(topicHint)
                  
                  Log.d("CommitmentTrigger", "承诺触发：${commitment.content}")
              } catch (e: Exception) {
                  Log.e("CommitmentTrigger", "承诺触发失败", e)
              } finally {
                  pendingResult.finish()
              }
          }
      }
  }
  ```
  注意：需要在 AndroidManifest.xml 中注册 receiver（Step 12）。
loop: false
verify:
  type: artifact
  path: app\src\main\java\com\agent\ta\service
  assert:
    kind: matches-glob
    value: "CommitmentTriggerReceiver.kt"

- [ ] **Step 12: AndroidManifest 注册 CommitmentTriggerReceiver + 添加权限**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\AndroidManifest.xml`：
  1. 在 `<manifest>` 标签内添加权限：
     ```xml
     <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
     <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
     ```
  2. 在 `<application>` 标签内添加 receiver 注册：
     ```xml
     <receiver
         android:name=".service.CommitmentTriggerReceiver"
         android:exported="false">
         <intent-filter>
             <action android:name="com.agent.ta.COMMITMENT_TRIGGER" />
         </intent-filter>
     </receiver>
     ```
  注意：USE_EXACT_ALARM 是 Android 13+ 权限，SCHEDULE_EXACT_ALARM 是 Android 12+ 权限。两个都加以兼容。
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 13: 扩展 LLM 输出 schema 新增 commitments 字段**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\domain\PromptBuilder.kt`，在 Zone C 的输出格式部分（约 760 行附近的 JSON schema 定义）新增 commitments 字段：
  1. 在 JSON 输出格式中添加：
     ```
     sb.appendLine("  \"commitments\": [")
     sb.appendLine("    {\"type\": \"appointment\", \"content\": \"约定内容\", \"triggerAt\": \"2026-07-30T15:00:00\", \"participants\": \"agent,user\"}")
     sb.appendLine("  ]")
     ```
  2. 在 futureEvents 字段之后添加 commitments 字段
  3. 添加 commitments 提取规则指引：
     ```
     sb.appendLine("- commitments 在以下场景输出（不是 futureEvents 的重复，是和用户的承诺/约定）：")
     sb.appendLine("  - 你答应了和用户一起做某事（各自同时看/听/玩）→ type=\"appointment\"")
     sb.appendLine("  - 你承诺要做某事（\"明天我帮你查\"）→ type=\"promise\"")
     sb.appendLine("  - 你要提醒用户做某事（\"明天叫你起床\"）→ type=\"reminder\"")
     sb.appendLine("- triggerAt 用 ISO 8601 格式（如 2026-07-30T15:00:00），从对话中换算")
     sb.appendLine("- participants: appointment 用 \"agent,user\"，promise 用 \"agent\"，reminder 用 \"user\"")
     sb.appendLine("- 没有承诺时留空数组")
     ```
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 14: 创建 CommitmentItem DTO + 扩展 AgentReply**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\data\remote\dto\TtsDto.kt`：
  1. 新增 CommitmentItem 数据类：
     ```kotlin
     @Serializable
     data class CommitmentItem(
         val type: String,           // appointment / promise / reminder
         val content: String,
         val triggerAt: String? = null,   // ISO 8601 字符串
         val participants: String = "agent"
     )
     ```
  2. 在 AgentReply 中新增 commitments 字段：
     ```kotlin
     val commitments: List<CommitmentItem> = emptyList()
     ```
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 15: 扩展 LlmClient 解析 commitments**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\data\remote\LlmClient.kt`，在 parseReply 方法中新增 commitments 解析：
  1. 参考 futureEvents 的解析逻辑（约 420 行）
  2. 从 JSON 中解析 commitments 数组为 List<CommitmentItem>
  3. 将解析结果赋值给 AgentReply.commitments
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 16: ChatInteractor 入库 commitments**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\domain\ChatInteractor.kt`，在存储 futureEvents 的位置（约 554 行）之后新增 commitments 入库逻辑：
  ```kotlin
  // 存承诺/约定（LLM 从对话中提取的）
  if (reply.commitments.isNotEmpty()) {
      reply.commitments.forEach { item ->
          val triggerAtTs = item.triggerAt?.let { parseIso8601ToTimestamp(it) }
          val commitment = CommitmentEntity(
              type = item.type,
              content = item.content,
              participants = item.participants,
              triggerAt = triggerAtTs,
              deadline = null,
              status = "pending",
              source = "chat",
              relatedMessageId = null  // 可选：传入当前消息 ID
          )
          val id = commitmentDao.insert(commitment)
          // 注册 AlarmManager 触发
          if (triggerAtTs != null && triggerAtTs > System.currentTimeMillis()) {
              CommitmentScheduler(context).scheduleCommitmentTrigger(commitment.copy(id = id))
          }
      }
      Log.d(TAG, "已存储 ${reply.commitments.size} 条承诺")
  }
  ```
  新增 `parseIso8601ToTimestamp` 辅助方法，用 java.time.LocalDateTime.parse + ZoneId 转换为 timestamp。
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 17: 扩展 LLM 输出 schema 新增 commitmentUpdates（完成识别）**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\domain\PromptBuilder.kt`，在 commitments 字段之后新增 commitmentUpdates 字段：
  1. JSON schema 中添加：
     ```
     sb.appendLine("  \"commitmentUpdates\": [")
     sb.appendLine("    {\"content\": \"承诺内容关键词\", \"status\": \"completed\"}")
     sb.appendLine("  ]")
     ```
  2. 添加规则指引：
     ```
     sb.appendLine("- commitmentUpdates 在以下场景输出：")
     sb.appendLine("  - 用户说\"看完了\"\"做完了\"等 → status=\"completed\"")
     sb.appendLine("  - 用户说\"算了吧\"\"不用了\" → status=\"cancelled\"")
     sb.appendLine("- content 用承诺内容的关键词匹配（如\"看电影\"匹配\"一起看《星际穿越》电影\"）")
     sb.appendLine("- 没有更新时留空数组")
     ```
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 18: ChatInteractor 处理 commitmentUpdates**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\domain\ChatInteractor.kt`，在 commitments 入库之后新增 commitmentUpdates 处理：
  ```kotlin
  // 处理承诺完成/取消（LLM 从对话中识别的）
  if (reply.commitmentUpdates.isNotEmpty()) {
      reply.commitmentUpdates.forEach { update ->
          // 按 content 关键词匹配 pending/triggered 状态的承诺
          val candidates = commitmentDao.getByStatus("pending") + commitmentDao.getByStatus("triggered")
          val matched = candidates.find { it.content.contains(update.content) || update.content.contains(it.content) }
          matched?.let {
              commitmentDao.updateStatus(it.id, update.status)
              if (update.status == "completed" || update.status == "cancelled") {
                  CommitmentScheduler(context).cancelCommitmentTrigger(it.id)
              }
              Log.d(TAG, "承诺状态更新：${it.content} → ${update.status}")
          }
      }
  }
  ```
  需要在 TtsDto.kt 中新增 CommitmentUpdateItem 数据类（content + status）。
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 19: 创建 CommitmentObserver（Heartbeat 兜底）**
action: 在 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\infrastructure\observer\` 目录新建 `CommitmentObserver.kt`：
  ```kotlin
  class CommitmentObserver : Observer {
      override val type = "commitment"
      private var lastDueCount = 0
      
      override fun collectSnapshot(): ObserverSnapshot {
          val now = System.currentTimeMillis()
          val dueCommitments = ServiceLocator.commitmentDao.getDueCommitments(now)
          val currentDueCount = dueCommitments.size
          val hasDelta = currentDueCount > lastDueCount
          lastDueCount = currentDueCount
          return ObserverSnapshot(
              type = type,
              data = dueCommitments.map { "${it.type}:${it.content}" },
              hasDelta = hasDelta
          )
      }
  }
  ```
  参考 TimeContextObserver 等现有观察者的实现模式。注意 Observer 接口的具体签名需要与现有观察者一致。
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 20: 注册 CommitmentObserver + Heartbeat 兜底触发**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\infrastructure\observer\ObserverRegistry.kt`，在注册其他观察者的位置添加：
  ```kotlin
  register(CommitmentObserver())
  ```
  然后修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\service\AgentEngine.kt` 或相关处理 observer 变化的位置，当 CommitmentObserver 有 delta 时，查询到期承诺并触发主动消息（作为 AlarmManager 的兜底）：
  ```kotlin
  // 在处理 observerSnapshots 变化的地方
  val commitmentSnapshot = observerSnapshots.find { it.type == "commitment" }
  if (commitmentSnapshot != null && commitmentSnapshot.hasDelta) {
      val dueCommitments = ServiceLocator.commitmentDao.getDueCommitments(System.currentTimeMillis())
      dueCommitments.forEach { commitment ->
          // 标记为 triggered 并触发主动消息
          ServiceLocator.commitmentDao.updateStatus(commitment.id, "triggered")
          val topicHint = "承诺时间到了：${commitment.content}"
          ChatInteractor(context).agentInitiate(topicHint)
      }
  }
  ```
  注意：需要找到实际处理 observer 变化的代码位置（可能在 BoredInitiator 或 ChatInteractor 中）。
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 21: 扩展 DailySummaryGenerator 生成结构化 DailyStateEntity**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\domain\DailySummaryGenerator.kt`，在生成 daily_summary 记忆的同时，生成 DailyStateEntity：
  1. 扩展 LLM prompt，要求输出结构化参数：
     ```
     sb.appendLine("请额外输出结构化参数（JSON）：")
     sb.appendLine("{\"mood\": -1.0~1.0, \"fatigue\": 0.0-1.0, \"stress\": 0.0-1.0, \"energy\": 0.0-1.0}")
     sb.appendLine("- mood: 昨天情绪（-1.0=低落, 0.0=平静, 1.0=愉悦）")
     sb.appendLine("- fatigue: 昨天疲劳（0.0=精神, 1.0=极度疲劳）")
     sb.appendLine("- stress: 昨天压力（0.0=轻松, 1.0=极度压力）")
     sb.appendLine("- energy: 昨天精力水平（0.0=低, 1.0=高）")
     ```
  2. 解析 LLM 输出中的结构化参数
  3. 从昨日作息提取 sleepTime（最后一个 unavailable slot 的 start）、wakeTime（第一个非 unavailable slot 的 start）、sleepDurationMin
  4. 从昨日 chat_message 表统计 hadInteractionWithUser 和 interactionCount
  5. 写入 DailyStateEntity（upsertPreservingCreatedAt）
  6. 同时保留原有的 daily_summary MemoryEntity 写入（兼容）
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 22: 重构 generateYesterdayRecall 为语义化总结**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\domain\DailyPlanner.kt` 的 `generateYesterdayRecall` 方法：
  1. 不再简单拼接时段，而是调 LLM 生成语义化总结
  2. prompt 注入：昨日作息时段 + 昨日对话摘要 + 昨日状态切换日志（可选）
  3. LLM 输出 100-200 字第一人称总结
  4. 去重检查改用 Step 1 新增的 `findOneByCategoryAndKeyword("daily_recall", yesterday)` 方法
  5. 保留 fallback：LLM 失败时降级为时段拼接
  ```kotlin
  private suspend fun generateYesterdayRecall(today: LocalDate, zoneId: ZoneId) {
      val yesterday = today.minusDays(1).format(DATE_FORMAT)
      // 用新 DAO 方法去重
      val existing = memoryDao.findOneByCategoryAndKeyword("daily_recall", yesterday)
      if (existing != null) return
      
      val yesterdaySchedule = dailyScheduleDao.getByDate(yesterday) ?: return
      val slots = parseSlots(yesterdaySchedule.slotsJson)
      if (slots.isEmpty()) return
      
      // 调 LLM 生成语义化总结
      val recallText = try {
          generateSemanticRecall(yesterday, slots, zoneId)
      } catch (e: Exception) {
          Log.w(TAG, "LLM 语义化回顾失败，降级为时段拼接", e)
          // fallback：时段拼接
          val recallSummary = slots.joinToString("；") { "${it.start}-${it.end} ${activityLabel(it)}" }
          "$yesterday：$recallSummary"
      }
      
      memoryDao.insert(MemoryEntity(
          type = "event", category = "daily_recall",
          content = recallText, importance = 2, source = "recall",
          createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
      ))
  }
  ```
  新增 `generateSemanticRecall` 私有方法，构造 prompt + 调 LLM。
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 23: 启动时补齐缺失的昨日回顾**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\domain\DailyPlanner.kt` 的 `generateTodaySchedule` 方法，在 `generateYesterdayRecall` 调用之前新增补齐逻辑：
  ```kotlin
  // 补齐昨天缺失的 DailyState（如果昨天有作息但没生成 DailyState）
  if (!isAgentSwitch) {
      val yesterday = today.minusDays(1).format(DATE_FORMAT)
      val existingState = dailyStateDao.getByDate(yesterday)
      if (existingState == null) {
          // 昨天有作息但没生成 DailyState，补齐
          val yesterdaySchedule = dailyScheduleDao.getByDate(yesterday)
          if (yesterdaySchedule != null) {
              try {
                  DailySummaryGenerator().generateSummaryForDate(today.minusDays(1), zoneId)
              } catch (e: Exception) {
                  Log.w(TAG, "补齐昨日 DailyState 失败", e)
              }
          }
      }
      generateYesterdayRecall(today, zoneId)
      // ... 原有逻辑
  }
  ```
  注意：DailySummaryGenerator 会同时生成 daily_summary 和 DailyStateEntity（Step 21 已扩展）。
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 24: PromptBuilder 注入昨日状态延续**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\domain\PromptBuilder.kt`，在 Zone B 新增"昨日状态延续"注入：
  1. 新增参数 `yesterdayCarryOver: String? = null`
  2. 在 build 方法签名添加该参数
  3. 在 Zone B 的"今天计划 vs 实际"之后注入：
     ```kotlin
     if (!yesterdayCarryOver.isNullOrBlank()) {
         sb.appendLine("【昨日状态延续】")
         sb.appendLine(yesterdayCarryOver)
         sb.appendLine()
     }
     ```
  4. 在 ChatInteractor 构造 prompt 时，查询昨日 DailyStateEntity 并构造延续文本：
     ```kotlin
     val yesterdayCarryOver = buildYesterdayCarryOver()
     // ...传入 promptBuilder.build(..., yesterdayCarryOver = yesterdayCarryOver)
     ```
  5. 新增 `buildYesterdayCarryOver` 方法（在 AgentEngine 或 ChatInteractor 中），读取 DailyStateEntity 并构造文本：
     ```
     昨晚你 01:15 才睡，今早 07:30 起（睡眠 6 小时 15 分钟，略不足）
     昨天活动：赶设计稿、加班到深夜
     昨天状态：压力大（stress=0.7）、疲劳（fatigue=0.8）、心情一般（mood=-0.2）
     今天调整建议：
     - 起床时间可以晚 30 分钟
     - 多安排休息时段（疲劳未恢复）
     - 心情一般，倾向独处而非社交活动
     ```
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 25: PromptBuilder 注入今日承诺列表**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\domain\PromptBuilder.kt`，在 Zone B 新增"今日承诺"注入：
  1. 新增参数 `todayCommitments: List<CommitmentEntity> = emptyList()`
  2. 在 Zone B 注入：
     ```kotlin
     if (todayCommitments.isNotEmpty()) {
         sb.appendLine("【今日承诺/约定】")
         sb.appendLine("今天你和用户有以下承诺：")
         todayCommitments.forEach { c ->
             val timeStr = c.triggerAt?.let { formatTime(it) } ?: "今日内"
             sb.appendLine("- $timeStr ${c.type}：${c.content}（参与者：${c.participants}）")
         }
         sb.appendLine("请在作息中安排相关时段，到点主动发起相关话题")
         sb.appendLine()
     }
     ```
  3. 在 ChatInteractor 构造 prompt 时，查询今日 pending/triggered 状态的承诺并传入
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 26: 创建 CreateCommitmentTool**
action: 在 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\domain\tool\builtin\` 目录新建 `CreateCommitmentTool.kt`：
  ```kotlin
  class CreateCommitmentTool : Tool {
      override val name = "create_commitment"
      override val description = """
          创建一个承诺/约定/提醒。
          - 答应和用户一起做某事（各自同时看/听/玩）→ type="appointment"
          - 承诺自己做某事 → type="promise"
          - 提醒用户做某事 → type="reminder"
          调用时机：
          - 用户说"下午一起看电影吧" → 你答应后调用
          - 你说"明天我帮你查 XXX" → 主动调用
          - 用户说"明天叫我起床" → 调用 reminder
      """.trimIndent()
      
      override val parameters = JsonObject(mapOf(
          "type" to JsonObject(mapOf("type" to "string", "enum" to listOf("appointment","promise","reminder"))),
          "content" to JsonObject(mapOf("type" to "string")),
          "triggerAt" to JsonObject(mapOf("type" to "string", "description" to "ISO 8601 格式，如 2026-07-30T15:00:00"))
      ))
      
      override suspend fun execute(args: JsonObject): String {
          val type = args["type"]?.asString ?: return "错误：缺少 type"
          val content = args["content"]?.asString ?: return "错误：缺少 content"
          val triggerAtStr = args["triggerAt"]?.asString
          val triggerAt = triggerAtStr?.let { parseIso8601(it) }
          
          val commitment = CommitmentEntity(
              type = type, content = content,
              participants = when (type) {
                  "appointment" -> "agent,user"
                  "promise" -> "agent"
                  "reminder" -> "user"
                  else -> "agent"
              },
              triggerAt = triggerAt,
              deadline = null,
              status = "pending",
              source = "tool"
          )
          val id = ServiceLocator.commitmentDao.insert(commitment)
          if (triggerAt != null && triggerAt > System.currentTimeMillis()) {
              CommitmentScheduler(ServiceLocator.appContext).scheduleCommitmentTrigger(commitment.copy(id = id))
          }
          return "承诺已记录：${commitment.content}（${formatTime(triggerAt)}）"
      }
  }
  ```
  参考 TodoTool 的实现模式。
loop: false
verify:
  type: artifact
  path: app\src\main\java\com\agent\ta\domain\tool\builtin
  assert:
    kind: matches-glob
    value: "CreateCommitmentTool.kt"

- [ ] **Step 27: 注册 CreateCommitmentTool 到 ToolRegistry**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\di\ServiceLocator.kt`，在 toolRegistry 的 register 调用中添加：
  ```kotlin
  register(CreateCommitmentTool())
  ```
  在 TodoTool 注册之后添加。
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 28: 承诺超时自动过期清理**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\service\AgentForegroundService.kt` 或 `AgentEngine.kt`，在跨天检测时（`ensureTodayScheduleFresh` 调用附近）新增承诺清理逻辑：
  ```kotlin
  // 清理过期承诺（超过 deadline 24 小时的 pending/triggered 自动标记为 expired）
  val now = System.currentTimeMillis()
  val expireBefore = now - 24 * 60 * 60 * 1000L  // 超过 deadline 24 小时
  val expired = commitmentDao.getExpiredCommitments(expireBefore)
  expired.forEach { commitment ->
      commitmentDao.updateStatus(commitment.id, "expired")
      CommitmentScheduler(context).cancelCommitmentTrigger(commitment.id)
  }
  // 清理已完成的旧承诺（30 天前的 completed/cancelled/expired）
  val oldCutoff = now - 30 * 24 * 60 * 60 * 1000L
  commitmentDao.deleteOldCompleted(oldCutoff)
  ```
  注意：需要找到实际跨天检测的代码位置。
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 29: 历史记忆清理策略**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\service\AgentForegroundService.kt` 或 `AgentEngine.kt`，在跨天检测时新增历史清理：
  ```kotlin
  // 清理 30 天前的 daily_plan 和 daily_recall（importance=2，价值递减）
  val cutoff30 = (now - 30L * 24 * 60 * 60 * 1000).toString()
  // 使用 MemoryDao 的 deleteBefore 或新增方法
  // 清理 90 天前的 daily_summary、daily_schedule、daily_state
  val cutoff90 = (now - 90L * 24 * 60 * 60 * 1000).toString()
  dailyScheduleDao.deleteBefore(cutoff90)
  // daily_state 表清理
  dailyStateDao.deleteBefore(cutoff90)
  ```
  注意：MemoryDao 需要新增 `deleteByCategoryBefore(category: String, beforeTs: Long)` 方法。
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 30: 扩展 prompt 注入承诺历史**
action: 修改 `e:\my_projects\AndroidStudioProjects\ta\app\src\main\java\com\agent\ta\domain\DailySummaryGenerator.kt`，在生成每日摘要时额外查询当天承诺及完成状态，注入摘要：
  1. 查询当天 commitments 表
  2. 注入 prompt：
     ```
     sb.appendLine("今天的承诺情况：")
     commitments.forEach { c ->
         sb.appendLine("- ${c.type}：${c.content}（状态：${c.status}）")
     }
     ```
  3. 让 LLM 在摘要中包含承诺完成情况："昨天答应了和用户一起看电影，下午 3 点触发，用户说看完了好评"
loop: false
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 31: 全量构建验证 + 回归测试**
action: 运行全量构建和测试，确保所有改动无回归：
  1. `.\gradlew.bat assembleDebug`
  2. `.\gradlew.bat testDebugUnitTest`
  3. 检查 SleepPhaseSplitter 4 个测试仍通过
  4. 检查无编译错误
loop: until build and tests pass
max_iterations: 3
verify: .\gradlew.bat assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL"

- [ ] **Step 32: 人工审查 Gate 1 - 数据模型与 DB 迁移**
action: 人工审查以下内容：
  1. DailyStateEntity 和 CommitmentEntity 字段定义是否完整
  2. DB 迁移 10→11 和 11→12 的 SQL 是否与 Entity 字段一致
  3. TaDatabase version 是否正确递增
  4. ServiceLocator 是否正确注入新 DAO
gate: human
loop: false
verify:
  type: human-review
  check: 数据模型和迁移正确性

- [ ] **Step 33: 人工审查 Gate 2 - 承诺触发机制**
action: 人工审查以下内容：
  1. CommitmentScheduler 的 AlarmManager 使用是否正确
  2. CommitmentTriggerReceiver 是否正确注册到 AndroidManifest
  3. 承诺触发后的主动消息流程是否正确
  4. CommitmentObserver 是否正确注册到 ObserverRegistry
  5. Heartbeat 兜底逻辑是否正确
gate: human
loop: false
verify:
  type: human-review
  check: 承诺触发机制正确性

- [ ] **Step 34: 人工审查 Gate 3 - 记忆增强与 Prompt 注入**
action: 人工审查以下内容：
  1. 语义化昨日总结的 LLM prompt 是否合理
  2. DailyStateEntity 的结构化参数解析是否健壮
  3. PromptBuilder 的昨日状态延续注入是否正确
  4. 今日承诺列表注入是否正确
  5. 历史清理策略是否安全（不会误删重要记忆）
gate: human
loop: false
verify:
  type: human-review
  check: 记忆增强与 Prompt 注入正确性
