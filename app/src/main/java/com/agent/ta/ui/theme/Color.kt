package com.agent.ta.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Vibe Chat 调色板 — 对齐设计稿 colors_and_type.css
 *
 * 设计系统：白底 + 浅青灰卡 + 气泡强区分
 * 主色统一 teal #1b5e5c
 *
 * 核心色值（来自设计稿 :root 变量）：
 * - primary:      #1b5e5c (主色 teal)
 * - primary-tint: #2f8784 (渐变起点)
 * - primary-deep: #134845 (渐变终点)
 * - primary-soft: rgba(27,94,92,0.10) (低透明主色底)
 * - primary-glow: rgba(27,94,92,0.28) (光晕)
 * - background:   #FFFFFF (纯白底)
 * - foreground:   #1A2B28 (深青黑文字)
 * - card:         #E8F1ED (浅青灰卡片)
 * - popover/muted:#F4F8F6 (极浅青绿)
 * - muted-fg:     #5A7570 (青灰次级文字)
 * - border:       #D0E0DA (青绿边框)
 * - bubble-in-bg: #F4F8F6 (接收气泡白底)
 * - bubble-in-border: #C8DDD6 (接收气泡浅青边框)
 *
 * 渐变：teal 单色相（tint → primary → deep），非双色相
 * 卡片：统一浅青灰 #E8F1ED，非多色卡片
 */

// ===== Light scheme =====
val md_primary_light = Color(0xFF1B5E5C)              // 主色 teal
val md_onPrimary_light = Color(0xFFFFFFFF)
val md_primaryContainer_light = Color(0xFFE8F1ED)    // primary-soft 浅青灰卡片底
val md_onPrimaryContainer_light = Color(0xFF134845)

val md_secondary_light = Color(0xFF2F8784)           // primary-tint
val md_onSecondary_light = Color(0xFFFFFFFF)
val md_secondaryContainer_light = Color(0xFFD9EBE9)  // 浅青绿柔和底
val md_onSecondaryContainer_light = Color(0xFF134845)

val md_tertiary_light = Color(0xFF16A34A)            // state-success 绿
val md_onTertiary_light = Color(0xFFFFFFFF)
val md_tertiaryContainer_light = Color(0xFFDCFCE7)
val md_onTertiaryContainer_light = Color(0xFF14532D)

val md_error_light = Color(0xFFEF4444)               // state-error 红
val md_onError_light = Color(0xFFFFFFFF)
val md_errorContainer_light = Color(0xFFFEE2E2)
val md_onErrorContainer_light = Color(0xFF7F1D1D)

val md_background_light = Color(0xFFF6F9F9)          // 页面背景（浅青灰，rgb(246,249,249)）
val md_onBackground_light = Color(0xFF1A2B28)        // 深青黑文字
val md_surface_light = Color(0xFFFFFFFF)
val md_onSurface_light = Color(0xFF1A2B28)           // 深青黑
val md_surfaceVariant_light = Color(0xFFF4F8F6)      // muted 极浅青绿
val md_onSurfaceVariant_light = Color(0xFF5A7570)    // muted-foreground 青灰
val md_surfaceContainerLowest_light = Color(0xFFFFFFFF)
val md_surfaceContainerLow_light = Color(0xFFF4F8F6) // popover 极浅青绿
val md_surfaceContainer_light = Color(0xFFE8F1ED)    // card 浅青灰卡
val md_surfaceContainerHigh_light = Color(0xFFD0E0DA) // border 青绿边框
val md_surfaceContainerHighest_light = Color(0xFFC8DDD6)
val md_outline_light = Color(0xFF5A7570)             // 青灰
val md_outlineVariant_light = Color(0xFFD0E0DA)      // 青绿边框
val md_scrim_light = Color(0xFF000000)
val md_inverseSurface_light = Color(0xFF1A2B28)
val md_inverseOnSurface_light = Color(0xFFF4F8F6)
val md_inversePrimary_light = Color(0xFF2F8784)

// ===== Dark scheme（暂保留，未深入设计）=====
val md_primary_dark = Color(0xFF5EEAD4)
val md_onPrimary_dark = Color(0xFF003734)
val md_primaryContainer_dark = Color(0xFF00504C)
val md_onPrimaryContainer_dark = Color(0xFFA0DBD7)

val md_secondary_dark = Color(0xFFC7D2FE)
val md_onSecondary_dark = Color(0xFF1B3531)
val md_secondaryContainer_dark = Color(0xFF3730A3)
val md_onSecondaryContainer_dark = Color(0xFFE0E7FF)

val md_tertiary_dark = Color(0xFF6EE7B7)
val md_onTertiary_dark = Color(0xFF003914)
val md_tertiaryContainer_dark = Color(0xFF1B4D22)
val md_onTertiaryContainer_dark = Color(0xFFB0F2C0)

val md_error_dark = Color(0xFFFFB4AB)
val md_onError_dark = Color(0xFF690005)
val md_errorContainer_dark = Color(0xFF93000A)
val md_onErrorContainer_dark = Color(0xFFFFDAD6)

val md_background_dark = Color(0xFF1A2B28)
val md_onBackground_dark = Color(0xFFE8F1ED)
val md_surface_dark = Color(0xFF1A2B28)
val md_onSurface_dark = Color(0xFFE8F1ED)
val md_surfaceVariant_dark = Color(0xFF3A504C)
val md_onSurfaceVariant_dark = Color(0xFFBFD0CA)
val md_surfaceContainerLowest_dark = Color(0xFF0F1A18)
val md_surfaceContainerLow_dark = Color(0xFF1A2B28)
val md_surfaceContainer_dark = Color(0xFF243835)
val md_surfaceContainerHigh_dark = Color(0xFF2F4744)
val md_surfaceContainerHighest_dark = Color(0xFF3A504C)
val md_outline_dark = Color(0xFF88A09A)
val md_outlineVariant_dark = Color(0xFF3A504C)
val md_scrim_dark = Color(0xFF000000)
val md_inverseSurface_dark = Color(0xFFE8F1ED)
val md_inverseOnSurface_dark = Color(0xFF1A2B28)
val md_inversePrimary_dark = Color(0xFF1B5E5C)

// ===== Vibe Chat 渐变色（teal 单色相，对齐设计稿）=====
val VibePrimaryTint = Color(0xFF2F8784)   // 渐变起点（primary-tint）
val VibePrimary = Color(0xFF1B5E5C)       // 主色（primary）
val VibePrimaryDeep = Color(0xFF134845)   // 渐变终点（primary-deep）
val VibePrimarySoft = Color(0xFFD9EBE9)   // 浅青绿柔和底（secondaryContainer）
val VibePrimaryGlow = Color(0x471B5E5C)   // rgba(27,94,92,0.28) 光晕
val VibeBackground = Color(0xFFF6F9F9)    // 页面背景（rgb(246,249,249)）

// ===== 卡片/表面（对齐设计稿）=====
val VibeCardDark = Color.White            // 卡片背景（白色，统一色）
val VibeForeground = Color(0xFF1A2B28)    // 卡片内深色字（vibe-foreground）
val VibeMuted = Color(0xFFF4F8F6)         // 极浅青绿底（vibe-muted）
val VibeMutedForeground = Color(0xFF5A7570) // 青灰次级文字（vibe-muted-foreground）

// ===== 接收气泡（对齐设计稿 vibe-bubble-in）=====
val VibeBubbleInBg = Color(0xFFF4F8F6)         // 接收气泡白底
val VibeBubbleInBorder = Color(0xFFC8DDD6)     // 接收气泡浅青边框

// ===== 语义状态色（对齐设计稿）=====
val VibeStateSuccess = Color(0xFF16A34A)
val VibeStateWarning = Color(0xFFD97706)
val VibeStateError = Color(0xA3FF2929)
val VibeStateInfo = Color(0xFF2563EB)

// ===== 多元标签色（对齐设计稿 tag 色，浅色底+深色字）=====
val VibeTagGreenBg = Color(0x1F16A34A)    // rgba(22,163,74,0.12)
val VibeTagGreenFg = Color(0xFF15803D)
val VibeTagIndigoBg = Color(0x1F4F46E5)   // rgba(79,70,229,0.12)
val VibeTagIndigoFg = Color(0xFF4338CA)
val VibeTagAmberBg = Color(0x1FD97706)    // rgba(217,119,6,0.12)
val VibeTagAmberFg = Color(0xFFB45309)
val VibeTagPinkBg = Color(0x1ADB2777)     // rgba(219,39,119,0.10)
val VibeTagPinkFg = Color(0xFFBE185D)
val VibeTagRedBg = Color(0x1AEF4444)      // rgba(239,68,68,0.10)
val VibeTagRedFg = Color(0xFFDC2626)
val VibeTagSkyBg = Color(0x1F0EA5E9)      // rgba(14,165,233,0.12)
val VibeTagSkyFg = Color(0xFF0284C7)
