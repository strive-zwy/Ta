package com.agent.ta.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 对话摘要实体（v2 L2 认知层）
 *
 * 分桶存储对话摘要，每个桶对应 CONV_SUMMARY_BUCKET_SIZE 条消息
 * 用于长对话场景节省 Token，同时保持上下文连贯性
 *
 * 桶 ID 计算：bucketId = (messageCount / BUCKET_SIZE) + 1
 * 例：20条消息 → bucketId=1，40条消息 → bucketId=2
 *
 * 摘要生成时机：
 * - 每达到桶大小时触发 LLM 生成
 * - 失败时降级为截断前 50 字，不阻塞主流程
 */
@Entity(
    tableName = "conversation_summaries",
    indices = [Index(value = ["agentId", "bucketId"])]
)
data class ConversationSummaryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 所属 Agent 实例 ID（多 Agent 数据隔离） */
    val agentId: Long,
    /** 桶 ID（从 1 开始递增） */
    val bucketId: Long,
    /** 该桶包含的消息起始 ID */
    val startMessageId: Long,
    /** 该桶包含的消息结束 ID */
    val endMessageId: Long,
    /** 摘要内容（最长 150 字） */
    val summary: String,
    /** 生成时间戳 */
    val createdAt: Long,
    /** 消息数量（通常等于 BUCKET_SIZE，最后一个桶可能少于） */
    val messageCount: Int
)
