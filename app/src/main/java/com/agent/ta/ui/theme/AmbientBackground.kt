package com.agent.ta.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradient
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agent.ta.data.model.AgentState

/**
 * AmbientBackground — 雾湖氛围流动 mesh 渐变背景
 *
 * 设计哲学：
 * - 不是静态颜色，而是流动的氛围
 * - 3-4 个色彩光斑在背景缓慢漂移（用 infiniteTransition）
 * - 颜色随 Agent 当前状态变化：
 *   - NORMAL：暖米青绿调（日常）
 *   - BUSY：深青绿调（专注）
 *   - IDLE：中性灰青调（闲适）
 *   - UNAVAILABLE：深蓝紫调（夜晚休息）
 * - 大幅 blur 让光斑变得柔和如雾湖
 * - 兼容 API 24（不依赖 RenderEffect）
 *
 * 使用方式：
 *   Box(modifier = Modifier.fillMaxSize()) {
 *       AmbientBackground(state = agentState)
 *       // 内容
 *   }
 */
@Composable
fun AmbientBackground(
    state: AgentState = AgentState.IDLE,
    modifier: Modifier = Modifier,
    intensity: Float = 1f
) {
    val palette = remember(state) { state.toPalette() }
    val density = LocalDensity.current

    // 3 个光斑的漂移动画（无限循环，每个不同周期）
    val transition = rememberInfiniteTransition(label = "ambientMesh")

    val blob1X by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(TaMotion.AMBENT_MESH_DRIFT_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blob1X"
    )
    val blob1Y by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(TaMotion.AMBENT_MESH_DRIFT_DURATION_MS + 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blob1Y"
    )

    val blob2X by transition.animateFloat(
        initialValue = 0.75f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(TaMotion.AMBENT_MESH_DRIFT_DURATION_MS + 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blob2X"
    )
    val blob2Y by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(TaMotion.AMBENT_MESH_DRIFT_DURATION_MS + 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blob2Y"
    )

    val blob3X by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(TaMotion.AMBENT_MESH_DRIFT_DURATION_MS + 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blob3X"
    )
    val blob3Y by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(TaMotion.AMBENT_MESH_DRIFT_DURATION_MS + 10000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blob3Y"
    )

    // 呼吸 alpha：让整体氛围缓慢"呼吸"
    val breathAlpha by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(TaMotion.STATUS_BREATH_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambientBreath"
    )

    Box(modifier = modifier.fillMaxSize()) {
        // 基础底色
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.baseColor)
        )

        // 3 个色彩光斑（大幅 blur 形成雾化效果）
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .blur(80.dp)
        ) {
            val w = size.width
            val h = size.height

            // 光斑 1
            drawRadialBlob(
                centerX = blob1X * w,
                centerY = blob1Y * h,
                radius = w * 0.55f,
                color = palette.blob1Color.copy(alpha = 0.6f * intensity * breathAlpha)
            )

            // 光斑 2
            drawRadialBlob(
                centerX = blob2X * w,
                centerY = blob2Y * h,
                radius = w * 0.5f,
                color = palette.blob2Color.copy(alpha = 0.55f * intensity * breathAlpha)
            )

            // 光斑 3
            drawRadialBlob(
                centerX = blob3X * w,
                centerY = blob3Y * h,
                radius = w * 0.45f,
                color = palette.blob3Color.copy(alpha = 0.5f * intensity * breathAlpha)
            )
        }

        // 噪声纹理（用细小的 alpha 渐变模拟，避免引入外部资源）
        // 不实际绘制噪声（性能考量），改用顶部和底部的 vignette
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // 顶部渐变（让顶部稍暗，增强景深）
            drawRectVignette(
                topColor = palette.vignetteColor.copy(alpha = 0.15f),
                bottomColor = Color.Transparent,
                height = h * 0.3f
            )
            // 底部渐变
            drawRectVignetteBottom(
                bottomColor = palette.vignetteColor.copy(alpha = 0.25f),
                topColor = Color.Transparent,
                startY = h * 0.7f,
                height = h * 0.3f
            )
        }
    }
}

/**
 * 雾湖状态调色板
 */
data class MistyLakePalette(
    val baseColor: Color,
    val blob1Color: Color,
    val blob2Color: Color,
    val blob3Color: Color,
    val vignetteColor: Color
)

/**
 * AgentState → 调色板映射
 * 每个状态都有独特的氛围色调
 */
private fun AgentState.toPalette(): MistyLakePalette {
    return when (this) {
        AgentState.NORMAL -> MistyLakePalette(
            baseColor = Color(0xFFFAF8F5),           // 米杏（日常）
            blob1Color = Color(0xFF1B5E5C),          // 雾湖青
            blob2Color = Color(0xFFB5834B),          // 暖米橙
            blob3Color = Color(0xFF8AB8B5),          // 浅青绿
            vignetteColor = Color(0xFF1B5E5C)
        )
        AgentState.BUSY -> MistyLakePalette(
            baseColor = Color(0xFFF5F2EC),           // 暖米杏（专注）
            blob1Color = Color(0xFF1B5E5C),          // 雾湖青（专注）
            blob2Color = Color(0xFFB5834B),          // 暖米橙
            blob3Color = Color(0xFF8AB8B5),          // 浅青绿
            vignetteColor = Color(0xFF1B5E5C)
        )
        AgentState.IDLE -> MistyLakePalette(
            baseColor = Color(0xFFFAF8F5),           // 米杏（闲适）
            blob1Color = Color(0xFFE8A555),          // 暖橙（轻松）
            blob2Color = Color(0xFF1B5E5C),          // 雾湖青
            blob3Color = Color(0xFFE7E3DA),          // 雾米色
            vignetteColor = Color(0xFF1B5E5C)
        )
        AgentState.UNAVAILABLE -> MistyLakePalette(
            baseColor = Color(0xFF0E1820),           // 深夜湖（深睡）
            blob1Color = Color(0xFF1B3A5C),          // 深蓝月光
            blob2Color = Color(0xFF2A1B5C),          // 深紫夜
            blob3Color = Color(0xFF1B5E5C),          // 雾湖青
            vignetteColor = Color(0xFF000000)
        )
        AgentState.LIGHT_SLEEP -> MistyLakePalette(
            baseColor = Color(0xFF1A2230),           // 浅夜湖（浅睡惊醒）
            blob1Color = Color(0xFF2A4A6C),          // 月光蓝
            blob2Color = Color(0xFF3A2B6C),          // 暖紫
            blob3Color = Color(0xFF2B6E5C),          // 雾湖青
            vignetteColor = Color(0xFF0A0E14)
        )
    }
}

/**
 * 绘制径向光斑
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRadialBlob(
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: Color
) {
    if (radius <= 0f) return
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, Color.Transparent),
            center = Offset(centerX, centerY),
            radius = radius
        ),
        center = Offset(centerX, centerY),
        radius = radius
    )
}

/**
 * 顶部 vignette
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRectVignette(
    topColor: Color,
    bottomColor: Color,
    height: Float
) {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(topColor, bottomColor)
        ),
        topLeft = Offset.Zero,
        size = androidx.compose.ui.geometry.Size(size.width, height)
    )
}

/**
 * 底部 vignette
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRectVignetteBottom(
    bottomColor: Color,
    topColor: Color,
    startY: Float,
    height: Float
) {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(topColor, bottomColor)
        ),
        topLeft = Offset(0f, startY),
        size = androidx.compose.ui.geometry.Size(size.width, height)
    )
}
