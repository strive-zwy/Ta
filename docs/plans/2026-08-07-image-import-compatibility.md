# Image Import Compatibility Implementation Plan

**Goal:** 让旧版 Agent 配置包中的头像按真实图片格式导入，自动修正扩展名和配置引用。

**Architecture:** 在导入策略层根据文件头识别图片类型，在 ZIP 解压层生成规范化目标路径并记录旧路径到新路径映射，最后在配置路径改写阶段应用映射。未知、损坏或非图片内容继续拒绝，不放宽 ZIP 路径和大小安全限制。

**Tech Stack:** Kotlin、Android BitmapFactory、Room-independent unit tests、Gradle

---

### Task 1: 图片格式识别

**Files:**
- Modify: `app/src/main/java/com/agent/ta/util/AgentImportPolicy.kt`
- Modify: `app/src/test/java/com/agent/ta/util/AgentImportPolicyTest.kt`

1. 为 JPEG、PNG、WebP、GIF、BMP、HEIF/HEIC、AVIF 编写失败测试。
2. 运行目标测试并确认失败。
3. 实现文件头识别和规范扩展名返回。
4. 重跑目标测试并确认通过。

### Task 2: 导入路径规范化

**Files:**
- Modify: `app/src/main/java/com/agent/ta/util/AgentConfigImporter.kt`
- Test: `app/src/test/java/com/agent/ta/util/AgentImportPolicyTest.kt`

1. 为错误扩展名到真实扩展名的规范化路径编写失败测试。
2. 解压头像时根据真实格式选择目标文件名并处理重名。
3. 记录旧 ZIP 路径到规范路径映射。
4. 在头像配置路径改写时应用映射。
5. 保持音频及未知格式的严格签名校验。

### Task 3: 验证

1. 运行 `:app:testDebugUnitTest`。
2. 运行 `:app:lintDebug`。
3. 运行 `:app:assembleDebug`。
4. 运行 `git diff --check`。
5. 使用旧版伪扩展名 ZIP 或等价测试包验证导入结果。
