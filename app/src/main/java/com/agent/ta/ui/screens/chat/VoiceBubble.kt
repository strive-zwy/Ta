package com.agent.ta.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.ta.ui.theme.VibePrimary

// ===== AI 风格语音组件本地色板（与 ChatScreen 保持一致）=====
private val AiPrimary = Color(0xFF2F8F89)             // 品牌色
private val AiAccentBlue = Color(0xFF5B8DEF)          // 辅助蓝（转文字）
private val AiTimeText = Color(0xFF98A2B3)            // 时间弱化灰
private val AiWaveStatic = Color(0xFF5C8F89)          // 波形静态色
private val AiWavePlaying = Color(0xFF2F8F89)         // 波形播放色

/**
 * Vibe Chat 风格语音消息组件（参考设计稿 chat-main.html 变体 1：纯语音播放态）
 *
 * 视觉结构：
 * ```
 * ┌─────────────────────────────┐
 * │ [▶] ━━━━━━━━━━━━━━━  0:08   │  ← 白色气泡，只含 play+wave+duration
 * └─────────────────────────────┘
 * [转文字]  14:23                  ← chip + 时间在气泡外（由 VoiceMessageFooter 渲染）
 *
 * （展开后）
 * ┌─────────────────────────────┐
 * │ 周六下午三点，在巷子里...      │  ← 独立卡片，在气泡外
 * └─────────────────────────────┘
 * ```
 *
 * 本 Composable 只渲染语音条主体（play + waveform + duration）。
 * "转文字"按钮和展开文字由 [VoiceMessageFooter] 在气泡外渲染。
 */
@Composable
fun VoiceBubble(
    isPlaying: Boolean,
    durationSec: Int,
    onTogglePlay: () -> Unit,
    isUser: Boolean,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }
    val playButtonScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "playButtonScale"
    )

    VoiceBarRow(
        isPlaying = isPlaying,
        durationSec = durationSec,
        onTogglePlay = onTogglePlay,
        isUser = isUser,
        playButtonScale = playButtonScale,
        onTogglePressed = { isPressed = it },
        modifier = modifier
    )
}

/**
 * 语音消息气泡外底部行：[转文字 chip] + [时间戳]，以及展开后的转写文字卡片。
 *
 * 设计稿对应：
 * ```html
 * <div class="flex items-center gap-2 px-1">
 *   <button class="flex items-center gap-1 text-xs font-medium" style="color: var(--vibe-primary);">
 *     <i data-lucide="languages" class="w-3 h-3"></i>
 *     转文字
 *   </button>
 *   <span class="text-[10px]" style="color: var(--vibe-muted-foreground);">14:23</span>
 * </div>
 * ```
 *
 * @param transcript 原始文字（TTS 合成时的 replyText），为 null 或空时不显示转文字 chip
 * @param timestamp 消息时间戳（毫秒）
 * @param timeText 已格式化的时间字符串
 */
@Composable
fun VoiceMessageFooter(
    transcript: String?,
    timestamp: Long,
    timeText: String,
    modifier: Modifier = Modifier
) {
    // showText 状态持久化（按 transcript 内容区分）
    var showText by rememberSaveable(transcript) { mutableStateOf(false) }

    Column(modifier = modifier) {
        // ===== 1. 转文字 chip + 时间戳（同一行，气泡外）=====
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!transcript.isNullOrBlank()) {
                TranscriptTextButton(
                    showText = showText,
                    onClick = { showText = !showText }
                )
            }
            Text(
                text = timeText,
                fontSize = 10.sp,
                color = AiTimeText
            )
        }

        // ===== 2. 展开的文字卡片（在 chip 下方，气泡外独立卡片）=====
        AnimatedVisibility(
            visible = showText && !transcript.isNullOrBlank(),
            enter = expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = 380f
                )
            ) + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = transcript ?: "",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 5
                )
            }
        }
    }
}

/**
 * 语音条横向布局：play → waveform → duration（Agent 侧）或反向（用户侧）
 */
@Composable
private fun VoiceBarRow(
    isPlaying: Boolean,
    durationSec: Int,
    onTogglePlay: () -> Unit,
    isUser: Boolean,
    playButtonScale: Float,
    onTogglePressed: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) {
            Text(
                formatDuration(durationSec),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
            WaveIndicator(isPlaying = isPlaying, isUser = isUser)
            Spacer(modifier = Modifier.width(8.dp))
            PlayButton(
                isPlaying = isPlaying,
                onTogglePlay = onTogglePlay,
                isUser = isUser,
                scale = playButtonScale,
                onTogglePressed = onTogglePressed
            )
        } else {
            PlayButton(
                isPlaying = isPlaying,
                onTogglePlay = onTogglePlay,
                isUser = isUser,
                scale = playButtonScale,
                onTogglePressed = onTogglePressed
            )
            Spacer(modifier = Modifier.width(10.dp))
            WaveIndicator(isPlaying = isPlaying, isUser = isUser)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                formatDuration(durationSec),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 48dp 圆形播放按钮 — 品牌色 #2F8F89 + 白色图标 + 轻微阴影
 * - Agent 消息：品牌色实色背景 + 白色图标
 * - 用户消息：白色半透明背景 + 白色图标（在品牌色气泡内）
 */
@Composable
private fun PlayButton(
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    isUser: Boolean,
    scale: Float,
    onTogglePressed: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .scale(scale)
            .shadow(
                elevation = 6.dp,
                shape = CircleShape,
                ambientColor = Color(0x292F8F89),
                spotColor = Color(0x292F8F89)
            )
            .clip(CircleShape)
            .background(
                if (isUser) Color.White.copy(alpha = 0.25f)
                else AiPrimary
            )
            .clickable {
                onTogglePlay()
                onTogglePressed(true)
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "暂停" else "播放",
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 波形指示器（24 根条形，更接近真实波形振幅分布）
 *
 * 静态分布参考设计稿的真实波形振幅序列。
 * 播放时所有条独立做 scaleY 动画，模拟实时音频波形跳动。
 */
@Composable
private fun WaveIndicator(isPlaying: Boolean, isUser: Boolean) {
    // 波形色：静态 #5C8F89，播放 #2F8F89（动态变化）；用户侧在品牌色气泡内用白色
    val color = when {
        isUser -> Color.White
        isPlaying -> AiWavePlaying
        else -> AiWaveStatic
    }

    // 24 根波形的高度分布（参考设计稿真实波形）
    val staticHeights = floatArrayOf(
        0.30f, 0.42f, 0.55f, 0.70f, 0.58f, 0.40f,
        0.62f, 0.80f, 0.95f, 0.88f, 0.70f, 0.50f,
        0.65f, 0.82f, 0.98f, 0.90f, 0.75f, 0.58f,
        0.45f, 0.66f, 0.82f, 0.74f, 0.50f, 0.36f
    )

    if (isPlaying) {
        val transition = rememberInfiniteTransition(label = "wave")
        val animValues = staticHeights.indices.mapIndexed { idx, _ ->
            transition.animateFloat(
                initialValue = staticHeights[idx] * 0.4f,
                targetValue = staticHeights[idx],
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 700 + (idx % 5) * 80,
                        delayMillis = idx * 30,
                        easing = androidx.compose.animation.core.LinearEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$idx"
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            animValues.forEachIndexed { idx, anim ->
                WaveBar(heightFraction = anim.value, color = color)
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            staticHeights.forEach { h ->
                WaveBar(heightFraction = h, color = color)
            }
        }
    }
}

@Composable
private fun WaveBar(heightFraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .width(2.5.dp)
            .height((14 * heightFraction).dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color.copy(alpha = 0.85f))
    )
}

/**
 * 转文字文本按钮 — 品牌辅助蓝 #5B8DEF（透明背景，仅图标+蓝字）
 * 作为页面第二强调色，与品牌青绿区分
 */
@Composable
private fun TranscriptTextButton(
    showText: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = if (showText) Icons.Default.AutoAwesome else Icons.Default.Subtitles,
            contentDescription = if (showText) "收起文字" else "转文字",
            tint = AiAccentBlue,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = if (showText) "收起文字" else "转文字",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = AiAccentBlue
        )
    }
}

/**
 * 秒 → "m:ss" 格式（如 0:08, 1:23）
 */
private fun formatDuration(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
