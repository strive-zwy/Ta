package com.agent.ta.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * TaMotion — 雾湖青调 Ambient Glassmorph 动效库
 *
 * 设计哲学：
 * - 每个元素都有"生命感"：消息入场、状态呼吸、按钮反馈都用 spring
 * - 优先 spring physics 而非 tween duration
 * - 多元素同时入场时用 stagger（错峰延迟）增加层次感
 * - 状态转换用 morph（形变）而非瞬间切换
 *
 * 参数参考 M3 Expressive MotionScheme.expressive()：
 * - Default spatial: dampingRatio=0.8f, stiffness=380f
 * - Fast: stiffness=1500f
 * - Slow: stiffness=500f
 * - Effects snap: stiffness=2000f, no bouncy
 */
object TaMotion {

    // ===== Spring specs =====

    /** 默认空间动效：M3E 等价，中等弹性 */
    fun <T> defaultSpatial(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = 380f
    )

    /** 快速空间动效：用于按钮点击、tab 切换 */
    fun <T> fastSpatial(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = 1500f
    )

    /** 慢速空间动效：用于大型组件入场（Hero 卡片、Sheet 展开） */
    fun <T> slowSpatial(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = 500f
    )

    /** 效果 snap：用于瞬间状态切换（音波柱、图标切换） */
    fun <T> effectsSnap(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 2000f
    )

    /** 强弹性：用于 Hero 头像呼吸、消息入场弹性 */
    fun <T> bouncySpatial(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = 200f
    )

    // ===== Stagger 入场 =====

    /**
     * 列表 stagger 入场延迟
     * 第 index 个元素延迟 = index * STAGGER_DELAY_MS
     * 最多 10 个元素后不再延迟
     */
    fun staggerDelayMs(index: Int): Int = (index.coerceAtMost(10)) * STAGGER_DELAY_MS

    const val STAGGER_DELAY_MS = 40

    // ===== 缓动曲线 =====

    /** M3E Emphasized 缓动：Accelerate then Decelerate */
    val EmphasizedEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    /** M3E Standard 缓动：柔和进出 */
    val StandardEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    // ===== 时长常量 =====

    const val MESSAGE_ENTER_DURATION_MS = 350
    const val CARD_ENTRANCE_DURATION_MS = 500
    const val TYPING_DOT_INTERVAL_MS = 400
    const val STATUS_BREATH_DURATION_MS = 2400
    const val AMBENT_MESH_DRIFT_DURATION_MS = 18000
    const val GLOW_PULSE_DURATION_MS = 1800
    const val SHIMMER_DURATION_MS = 1200

    // ===== 默认 elevation =====

    val DEFAULT_ELEVATION: Dp = 2.dp
    val HERO_ELEVATION: Dp = 6.dp
    val FLOATING_ELEVATION: Dp = 8.dp

    // ===== 入场动画组合 =====

    /**
     * 消息入场动画组合
     * - 从下方滑入（slideInVertically）
     * - 同时缩放放大（scaleIn 0.9 → 1.0）
     * - 同时淡入
     * - 用 spring 而非 tween，避免线性机械感
     */
    val MessageEnter: AnimatedVisibilityTransition = AnimatedVisibilityTransition(
        enter = slideInVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = 380f
            ),
            initialOffsetY = { it / 4 }
        ) + scaleIn(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = 380f
            ),
            initialScale = 0.92f
        ) + fadeIn(
            animationSpec = tween(MESSAGE_ENTER_DURATION_MS, easing = EmphasizedEasing)
        ),
        exit = slideOutVertically() + scaleOut(targetScale = 0.92f) + fadeOut()
    )

    /**
     * 卡片入场动画组合
     * - 从下方滑入（更大距离）
     * - 缩放放大
     * - 淡入
     */
    val CardEntrance: AnimatedVisibilityTransition = AnimatedVisibilityTransition(
        enter = slideInVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = 500f
            ),
            initialOffsetY = { it / 2 }
        ) + scaleIn(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = 500f
            ),
            initialScale = 0.85f
        ) + fadeIn(
            animationSpec = tween(CARD_ENTRANCE_DURATION_MS, easing = EmphasizedEasing)
        ),
        exit = slideOutVertically() + scaleOut(targetScale = 0.85f) + fadeOut()
    )

    // ===== 常用 ease =====

    /** 从 fade 起步的入场，用于次级元素 */
    val FadeSpringEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
}

/**
 * 封装 AnimatedVisibility 的 enter/exit
 */
data class AnimatedVisibilityTransition(
    val enter: androidx.compose.animation.EnterTransition,
    val exit: androidx.compose.animation.ExitTransition
)
