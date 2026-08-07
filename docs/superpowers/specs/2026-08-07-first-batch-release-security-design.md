# 第一批发布安全改造设计

## 目标

在不改变现有模型配置界面和用户操作方式的前提下，完成第一批发布前安全加固：保护 LLM API Key、关闭系统备份、阻止未签名 Release 发布、降低生产日志隐私泄露风险，并提高 `.agent.zip` 导入的资源边界安全性。

## 范围

### API Key 存储

`ModelEntry` 保留现有字段和调用接口，作为运行时对象继续携带 `apiKey`。持久化时将模型元数据和密钥分离：

- 普通 `SharedPreferences` 只保存 `id/name/baseUrl/model`。
- `EncryptedSharedPreferences` 按模型 ID 保存 LLM API Key。
- `llmModels` 读取时从加密区组装运行时 `ModelEntry`。
- 新增、修改、删除和切换模型时同步处理加密区。
- 旧版 `llm_models_json` 中的 API Key 执行一次性迁移。
- 迁移只有在加密写入成功后才清除旧 JSON 中的 Key。
- 加密存储初始化失败时不回退到明文，按未配置处理。

### 系统备份

关闭 Android Auto Backup 与设备迁移，不让聊天、记忆、模型配置、API Key、本地头像和语音资源被系统自动备份。

### Release 签名

- Release 构建必须拥有完整 keystore 配置。
- 缺失配置时 Gradle 直接失败，不允许回退 debug 签名。
- Release CI 在上传前验证 APK 签名证书。
- Debug 构建继续使用 debug 签名。
- 本地开发不要求提交真实 keystore；只允许通过本地配置或 CI Secrets 提供。

### 隐私日志

Release 不输出以下内容：

- 聊天正文。
- 记忆内容。
- 承诺内容。
- Prompt 和导演指令。
- LLM 原始响应。
- TTS 原始响应。
- API Key、音频 Base64 和本地敏感路径。

保留非敏感诊断信息，如请求类型、耗时、状态码、响应长度、错误分类和内部 ID。Debug 日志也不输出 API Key 或完整音频数据。

### Agent ZIP 导入

导入器增加以下边界：

- 限制源 ZIP 大小。
- 限制条目数量。
- 限制单条目解压大小。
- 限制总解压大小。
- 拒绝绝对路径、路径穿越、符号链接和目录外引用。
- 只允许 `agent.json`、头像目录和语音目录中的受支持文件类型。
- 资源路径必须通过 canonical path containment 校验。
- 头像和音频校验文件头与扩展名一致。
- 任意导入失败都删除临时导入目录。

## 不在本批范围

- `agentId` 异步贯穿和跨 Agent 回写修复。
- 承诺任务两阶段状态机。
- `dataSync` 前台服务重构。
- 聊天分页和 Compose 性能改造。

## 验证

- JVM 测试覆盖密钥迁移、密钥删除、普通 Preferences 脱敏和 ZIP 边界。
- `:app:testDebugUnitTest` 必须通过。
- `:app:lintDebug` 必须通过。
- `:app:assembleDebug` 必须通过。
- 配置真实签名环境时，Release APK 必须通过 `apksigner verify --print-certs`。
- 静态检查确认 Release 不再引用 debug signing fallback。
