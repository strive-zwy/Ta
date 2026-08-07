package com.agent.ta.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 承诺/约定/提醒记录
 *
 * 三种类型：
 * - appointment：双方约定（如"下午3点一起看电影"，各自同时看）
 * - promise：Agent 承诺（如"明天我帮你查 XXX"）
 * - reminder：提醒用户（如"明天叫我起床"）
 *
 * 与 FutureEventEntity 的区别：
 * - FutureEventEntity 是纯事件（"下周三漫展"），与用户无关
 * - CommitmentEntity 是 Agent 与用户之间的承诺/约定
 */
@Entity(
    tableName = "commitments",
    indices = [Index(value = ["agentId", "status", "triggerAt"])]
)
data class CommitmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 所属 Agent 实例 ID（多 Agent 数据隔离） */
    val agentId: Long,
    val type: String,           // appointment（双方约定）/ promise（Agent 承诺）/ reminder（提醒用户）
    val content: String,        // "一起看《星际穿越》电影"
    val participants: String,   // "agent,user" / "agent" / "user"
    val triggerAt: Long?,       // 精确触发时间戳（毫秒），null 表示无精确触发时间
    val deadline: Long?,        // 截止时间戳（毫秒），null 表示无截止
    val status: String,         // pending / claimed / delivered / completed / cancelled / expired / failed
    val source: String,         // chat（LLM 被动提取）/ tool（工具主动创建）/ manual
    val relatedMessageId: Long?, // 关联的对话消息 ID（追溯"在哪句答应的"）
    val claimedAt: Long? = null,
    val retryCount: Int = 0,
    val nextRetryAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
