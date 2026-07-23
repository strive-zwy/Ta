package com.agent.ta.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 聊天消息实体
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val direction: String,          // inbound | outbound
    val text: String?,              // 文本内容
    val audioPath: String?,         // 语音文件路径
    val directorPrompt: String?,    // 生成时用的导演指令（outbound 才有）
    val state: String,              // 发送时 Agent 的状态
    val status: String,             // received | pending | replied | sent
    val createdAt: Long,            // 创建时间戳
    val repliedAt: Long? = null,    // 回复时间戳
    /** 旁白/动作描述（不进入语音合成，仅在 UI 以浅色斜体展示，outbound 才有） */
    val action: String? = null,
    /** 语音时长（秒），合成后落库，UI 展示真实时长而非写死 5 秒 */
    val audioDurationSec: Int? = null,
    /** 表情消息（emoji 字符，如 😄）。非 null 表示这是一条纯表情消息，UI 渲染大字号 emoji 而非文字气泡 */
    val emoji: String? = null
)
