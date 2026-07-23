package com.agent.ta.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * M3 Shapes 体系
 *
 * 用统一 token 替代散落各处的 4/8/12/16dp 硬编码
 *
 * Vibe Chat 重设计：
 * - 气泡使用 24dp 圆角（更柔和现代）
 * - 输入栏 / 大组件使用 28dp（pill 风）
 * - 小组件 / chip 使用 10-16dp
 */
val TaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),     // chip / 小标签
    small = RoundedCornerShape(12.dp),          // 小按钮 / 输入框
    medium = RoundedCornerShape(16.dp),         // 消息气泡默认
    large = RoundedCornerShape(20.dp),          // 大 Card / 图片
    extraLarge = RoundedCornerShape(28.dp)      // BottomSheet / FAB / 输入栏
)

/**
 * 消息气泡专用形状：24dp 圆角 + 发送方一侧顶角缩小（8dp）
 *
 * 视觉效果：消息从发送方"探出"，更接近 iMessage / 微信原生质感
 *
 * - receivedBubbleShape：左上角 8dp（Agent 消息从左侧探出）
 * - sentBubbleShape：右上角 8dp（用户消息从右侧探出）
 */
val ReceivedBubbleShape = RoundedCornerShape(
    topStart = 8.dp,
    topEnd = 24.dp,
    bottomEnd = 24.dp,
    bottomStart = 24.dp
)

val SentBubbleShape = RoundedCornerShape(
    topStart = 24.dp,
    topEnd = 8.dp,
    bottomEnd = 24.dp,
    bottomStart = 24.dp
)

/**
 * 语音转文字卡片形状：24dp 一致圆角 + 左上角 8dp（与 Agent 气泡呼应）
 */
val VoiceTranscriptCardShape = RoundedCornerShape(
    topStart = 8.dp,
    topEnd = 24.dp,
    bottomEnd = 24.dp,
    bottomStart = 24.dp
)
