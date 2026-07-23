package com.agent.ta.ui.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ===== AI Agent Studio 共享色板（所有 Agent 配置页面统一使用）=====
val AiBg = Color(0xFFF7F9F8)              // 页面背景
val AiCard = Color.White                   // 卡片纯白
val AiPrimary = Color(0xFF2F8F89)          // 主品牌色（低饱和青绿）
val AiPrimaryDeep = Color(0xFF267A74)      // 渐变终点
val AiTextPrimary = Color(0xFF1A2B28)      // 主文字
val AiTextSecondary = Color(0xFF5A7570)    // 次文字
val AiTextTertiary = Color(0xFF98A2B3)     // 三级文字
val AiBorder = Color(0xFFEEF2F4)           // 分割线/边框
val AiInputBg = Color(0xFFF4F6F8)          // 输入框浅灰背景
val AiInputBgFocused = Color(0xFFE8F0EE)   // 输入框聚焦背景
val AiShadowColor = Color(0x141B5E5C)      // 极淡 teal 阴影
// 低饱和辅助色（用于 Chip / 标签）
val AiChipPinkBg = Color(0xFFFCE7F3)
val AiChipPinkFg = Color(0xFFBE185D)
val AiChipAmberBg = Color(0xFFFEF3C7)
val AiChipAmberFg = Color(0xFFB45309)
val AiChipGreenBg = Color(0xFFD1FAE5)
val AiChipGreenFg = Color(0xFF15803D)
val AiChipRedBg = Color(0xFFFEE2E2)
val AiChipRedFg = Color(0xFFDC2626)
val AiChipIndigoBg = Color(0xFFE0E7FF)
val AiChipIndigoFg = Color(0xFF4338CA)
val AiChipMutedBg = Color(0xFFF4F6F8)
val AiChipMutedFg = Color(0xFF5A7570)
val AiChipPrimaryBg = Color(0xFFE0F2F1)
val AiChipPrimaryFg = Color(0xFF2F8F89)

/**
 * AI Agent Studio 共享 UI 组件
 *
 * 设计参考：ChatGPT GPTs / Character.AI / Apple Settings
 * - 背景 #F7F9F8，纯白卡片 + 24dp 大圆角 + 轻微阴影
 * - 主品牌色低饱和青绿 #2F8F89
 * - 输入框浅灰背景无边框
 * - 高留白、低饱和、现代 AI 产品风格
 */

/** sticky 顶栏：返回按钮 + 标题 + 副标题（极简，无分割线） */
@Composable
fun VibeTopBar(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AiBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AiTextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 22.sp,
                    color = AiTextPrimary
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = AiTextTertiary
                    )
                }
            }
        }
    }
}

/** AI Studio 配置卡片：纯白底 + 24dp 大圆角 + 轻柔阴影 + 标题 + 可选描述 */
@Composable
fun ConfigCard(
    title: String,
    description: String? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 3.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = AiShadowColor,
                spotColor = AiShadowColor
            )
            .clip(RoundedCornerShape(24.dp))
            .background(AiCard)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = AiTextPrimary
        )
        if (description != null) {
            Text(
                text = description,
                fontSize = 12.sp,
                color = AiTextTertiary,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/** 兼容旧调用（无 description 参数） */
@Composable
fun ConfigCard(
    title: String,
    content: @Composable () -> Unit
) = ConfigCard(title = title, description = null, content = content)

/** AI Studio 输入框：浅灰背景无边框 + label 在上方 */
@Composable
fun VibeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    singleLine: Boolean = false,
    isPassword: Boolean = false,
    enabled: Boolean = true,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (isFocused) AiPrimary else AiTextSecondary,
            modifier = Modifier.padding(bottom = 5.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (isFocused) AiInputBgFocused else AiInputBg)
                .alpha(if (enabled) 1f else 0.4f)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty() && placeholder.isNotBlank()) {
                        Text(
                            text = placeholder,
                            fontSize = 14.sp,
                            color = AiTextTertiary
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        singleLine = singleLine,
                        visualTransformation = if (isPassword) PasswordVisualTransformation()
                                               else VisualTransformation.None,
                        interactionSource = interactionSource,
                        textStyle = TextStyle(
                            fontSize = 14.sp,
                            color = AiTextPrimary,
                            fontFamily = if (isPassword) FontFamily.Monospace else null
                        ),
                        cursorBrush = SolidColor(AiPrimary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (trailingIcon != null) {
                    trailingIcon()
                }
            }
        }
    }
}

/**
 * 标签芯片类型（低饱和配色）
 */
enum class VibeChipType {
    DEFAULT,
    PERSONALITY,
    CATCHPHRASE,
    INTEREST,
    TABOO,
    EMOJI,
    NEUTRAL
}

/** 标签芯片（低饱和颜色，现代 AI 风格） */
@Composable
fun VibeChip(
    text: String,
    onDelete: (() -> Unit)? = null,
    chipType: VibeChipType = VibeChipType.DEFAULT
) {
    val (bgColor, fgColor) = when (chipType) {
        VibeChipType.PERSONALITY -> AiChipPinkBg to AiChipPinkFg
        VibeChipType.CATCHPHRASE -> AiChipAmberBg to AiChipAmberFg
        VibeChipType.INTEREST   -> AiChipGreenBg to AiChipGreenFg
        VibeChipType.TABOO      -> AiChipRedBg to AiChipRedFg
        VibeChipType.EMOJI      -> AiChipMutedBg to AiChipMutedFg
        VibeChipType.NEUTRAL    -> AiChipMutedBg to AiChipMutedFg
        VibeChipType.DEFAULT    -> AiChipPrimaryBg to AiChipPrimaryFg
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = fgColor,
            fontWeight = FontWeight.Medium
        )
        if (onDelete != null) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "×",
                    fontSize = 14.sp,
                    color = fgColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** 区块标题（卡片内小区块分隔，更轻量） */
@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = AiTextSecondary,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
    )
}

/** AI Studio 底部悬浮保存按钮（统一 Action Bar 风格） */
@Composable
fun AiSaveButton(
    justSaved: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .background(AiBg.copy(alpha = 0.96f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(bottom = 12.dp)
    ) {
        val gradient = if (justSaved) {
            Brush.linearGradient(listOf(AiPrimary, AiPrimaryDeep))
        } else {
            Brush.linearGradient(listOf(AiPrimary, AiPrimaryDeep))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(16.dp),
                    ambientColor = AiPrimary.copy(alpha = 0.3f),
                    spotColor = AiPrimary.copy(alpha = 0.3f)
                )
                .clip(RoundedCornerShape(16.dp))
                .background(gradient)
                .alpha(if (enabled) 1f else 0.5f)
                .clickable(enabled = enabled && !justSaved) { onClick() }
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (justSaved) "已保存" else "保存",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
