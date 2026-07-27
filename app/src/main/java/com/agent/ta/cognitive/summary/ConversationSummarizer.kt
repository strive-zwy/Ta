package com.agent.ta.cognitive.summary

import android.util.Log
import com.agent.ta.data.local.dao.ChatMessageDao
import com.agent.ta.data.local.dao.ConversationSummaryDao
import com.agent.ta.data.local.entity.ChatMessageEntity
import com.agent.ta.data.local.entity.ConversationSummaryEntity
import com.agent.ta.data.remote.LlmClient
import com.agent.ta.data.remote.dto.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 对话摘要生成器（L2 认知层）
 *
 * 设计参考：MochiBot 的对话摘要分桶机制
 *
 * 分桶策略：
 * - 每 CONV_SUMMARY_BUCKET_SIZE 条消息生成一个摘要
 * - 摘要独立持久化到 Room DB，可精确召回历史任意时间段
 * - 摘要最长 SUMMARY_MAX_LENGTH 字，避免 Token 占用过大
 *
 * 三级缓存：
 * - L1 内存缓存：本次会话内复用
 * - L2 Room DB：跨会话持久化
 * - L3 LLM 生成：DB 未命中时调用 LLM 生成并持久化
 *
 * 失败降级：
 * - LLM 调用失败时降级为截断前 50 字，不阻塞主流程
 *
 * 使用场景：
 * - 主回复路径：getPriorSummaries() 注入 Prompt Zone B
 * - 后台预热：prewarmNextBucket() 预生成下一个桶
 */
class ConversationSummarizer(
    private val llmClient: LlmClient,
    private val summaryDao: ConversationSummaryDao,
    private val chatDao: ChatMessageDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    /** L1 内存缓存：bucketId -> summary */
    private val l1Cache = mutableMapOf<Long, String>()
    private val cacheMutex = Mutex()

    /**
     * 获取当前桶之前的所有桶摘要合并文本
     *
     * 用于注入 Prompt Zone B，让 LLM 知道之前聊过什么
     *
     * @param currentBucketId 当前桶 ID（不含）
     * @return 合并后的摘要文本，无摘要时返回 null
     */
    suspend fun getPriorSummaries(currentBucketId: Long): String? {
        return try {
            val summaries = summaryDao.getPriorSummaries(currentBucketId)
            if (summaries.isEmpty()) return null

            val mergedText = summaries.joinToString(" | ") { entity ->
                "第${entity.bucketId}段：${entity.summary}"
            }

            if (mergedText.length > MAX_MERGED_LENGTH) {
                mergedText.take(MAX_MERGED_LENGTH) + "..."
            } else {
                mergedText
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取历史摘要失败: ${e.message}", e)
            null
        }
    }

    /**
     * 检查并生成新桶的摘要
     *
     * 当消息数达到桶大小时触发
     * 应在每次 Agent 回复后调用
     */
    suspend fun checkAndGenerateSummary(allMessages: List<ChatMessageEntity>) {
        val totalMessages = allMessages.size
        val expectedBuckets = totalMessages / BUCKET_SIZE

        if (expectedBuckets == 0L) {
            Log.v(TAG, "消息数 $totalMessages 不足一个桶（$BUCKET_SIZE），跳过摘要生成")
            return
        }

        // 检查哪些桶还没生成摘要
        val existingMaxBucketId = summaryDao.getMaxBucketId() ?: 0

        for (bucketId in (existingMaxBucketId + 1)..expectedBuckets) {
            val startIndex = ((bucketId - 1) * BUCKET_SIZE).toInt()
            val endIndex = (bucketId * BUCKET_SIZE).toInt().coerceAtMost(totalMessages)
            val bucketMessages = allMessages.subList(startIndex, endIndex)

            if (bucketMessages.isEmpty()) continue

            // 后台生成，不阻塞主流程
            scope.launch {
                generateAndPersist(bucketId, bucketMessages)
            }
        }
    }

    /**
     * 后台预热：预生成下一个桶的摘要
     *
     * 在用户长时间未响应时调用，提前生成可能需要的摘要
     */
    suspend fun prewarmNextBucket() {
        try {
            val allMessages = chatDao.getAll()
            val totalMessages = allMessages.size
            val nextBucketId = (summaryDao.getMaxBucketId() ?: 0) + 1
            val nextBucketStart = ((nextBucketId - 1) * BUCKET_SIZE).toInt()

            // 仅当下一桶的消息数达到阈值时预热
            val availableMessages = totalMessages - nextBucketStart
            if (availableMessages < BUCKET_SIZE) {
                Log.v(TAG, "预热跳过：下一桶消息数不足（$availableMessages/$BUCKET_SIZE）")
                return
            }

            val bucketMessages = allMessages.subList(
                nextBucketStart,
                (nextBucketStart + BUCKET_SIZE.toInt()).coerceAtMost(totalMessages)
            )

            // 检查是否已生成
            if (summaryDao.getByBucketId(nextBucketId) != null) {
                Log.v(TAG, "桶 #$nextBucketId 已存在摘要，跳过预热")
                return
            }

            scope.launch {
                generateAndPersist(nextBucketId, bucketMessages)
                Log.d(TAG, "桶 #$nextBucketId 预热完成")
            }
        } catch (e: Exception) {
            Log.e(TAG, "预热失败: ${e.message}", e)
        }
    }

    /**
     * 获取当前桶 ID（基于消息总数计算）
     */
    suspend fun getCurrentBucketId(): Long {
        val totalMessages = chatDao.getAll().size
        return (totalMessages / BUCKET_SIZE) + 1
    }

    /**
     * 生成并持久化摘要
     *
     * 失败降级：LLM 失败时用截断前 50 字兜底
     */
    private suspend fun generateAndPersist(bucketId: Long, messages: List<ChatMessageEntity>) {
        try {
            val summary = generateSummaryViaLlm(messages)

            val entity = ConversationSummaryEntity(
                bucketId = bucketId,
                startMessageId = messages.first().id,
                endMessageId = messages.last().id,
                summary = summary,
                createdAt = System.currentTimeMillis(),
                messageCount = messages.size
            )

            summaryDao.insert(entity)

            // 更新 L1 缓存
            cacheMutex.withLock {
                l1Cache[bucketId] = summary
            }

            Log.d(TAG, "桶 #$bucketId 摘要已生成并持久化（${messages.size}条消息 → ${summary.length}字）")
        } catch (e: Exception) {
            Log.e(TAG, "生成桶 #$bucketId 摘要失败: ${e.message}", e)
        }
    }

    /**
     * 通过 LLM 生成摘要
     *
     * 失败时降级为截断前 50 字
     */
    private suspend fun generateSummaryViaLlm(messages: List<ChatMessageEntity>): String {
        return try {
            val conversationText = messages.joinToString("\n") { msg ->
                val role = if (msg.direction == "inbound") "用户" else "Agent"
                val content = msg.text?.take(100) ?: ""
                "$role: $content"
            }

            val prompt = buildSummaryPrompt(conversationText)
            val messages_for_llm = listOf(
                ChatMessage("system", prompt),
                ChatMessage("user", "请生成上述对话的摘要")
            )

            val response = llmClient.chat(messages_for_llm)
            val summary = response.replyText.take(SUMMARY_MAX_LENGTH).trim()

            if (summary.isBlank()) {
                Log.w(TAG, "LLM 返回空摘要，降级为截断")
                fallbackSummary(messages)
            } else {
                summary
            }
        } catch (e: Exception) {
            Log.w(TAG, "LLM 生成摘要失败，降级为截断: ${e.message}")
            fallbackSummary(messages)
        }
    }

    /**
     * 降级摘要：取最后一条用户消息的前 50 字
     */
    private fun fallbackSummary(messages: List<ChatMessageEntity>): String {
        val lastUserMsg = messages.lastOrNull { it.direction == "inbound" }
        val content = lastUserMsg?.text?.take(50) ?: "对话摘要生成失败"
        return "聊到了：$content"
    }

    /**
     * 构造摘要生成 Prompt
     */
    private fun buildSummaryPrompt(conversationText: String): String {
        return buildString {
            appendLine("你是对话摘要生成器。请将以下对话压缩为不超过 $SUMMARY_MAX_LENGTH 字的摘要。")
            appendLine()
            appendLine("要求：")
            appendLine("1. 保留关键信息：用户提到的事实、约定、偏好、重要事件")
            appendLine("2. 忽略寒暄和重复内容")
            appendLine("3. 用第三人称描述（如「用户提到...」「Agent 分享了...」）")
            appendLine("4. 输出纯文本，不要序号、不要 markdown 格式")
            appendLine("5. 摘要长度严格控制在 $SUMMARY_MAX_LENGTH 字以内")
            appendLine()
            appendLine("【对话内容】")
            appendLine(conversationText)
        }
    }

    companion object {
        private const val TAG = "ConversationSummarizer"

        /** 桶大小：每 20 条消息生成一个摘要 */
        const val BUCKET_SIZE = 20L

        /** 单个摘要最大长度 */
        const val SUMMARY_MAX_LENGTH = 150

        /** 合并摘要最大长度（注入 Prompt 时） */
        const val MAX_MERGED_LENGTH = 500
    }
}
