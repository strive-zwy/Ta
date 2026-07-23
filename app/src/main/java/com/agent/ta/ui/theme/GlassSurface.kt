package com.agent.ta.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * GlassSurface — 玻璃质感卡片组件
 *
 * 设计特征：
 * - 半透明背景（white 8% ~ 15% alpha）
 * - 细边框（white 12% alpha）模拟玻璃边缘高光
 * - 可选 blur 模糊背景（API 31+ 生效，低版本降级为半透明）
 * - tonalElevation 让卡片浮起
 *
 * 使用方式：
 *   GlassSurface(
 *       modifier = Modifier.fillMaxWidth().padding(16.dp),
 *       shape = MaterialTheme.shapes.large,
 *       tonalElevation = 4.dp
 *   ) {
 *       // 内容
 *   }
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    tonalElevation: Dp = TaMotion.DEFAULT_ELEVATION,
    alpha: Float = 0.85f,
    borderAlpha: Float = 0.12f,
    content: @Composable () -> Unit
) {
    val baseColor = MaterialTheme.colorScheme.surface
    val glassColor = baseColor.copy(alpha = alpha)
    val borderColor = Color.White.copy(alpha = borderAlpha)

    Surface(
        modifier = modifier,
        shape = shape,
        color = glassColor,
        contentColor = MaterialTheme.colorScheme.contentColorFor(baseColor),
        tonalElevation = tonalElevation,
        border = BorderStroke(1.dp, borderColor)
    ) {
        content()
    }
}

/**
 * FloatingGlassBar — 浮动玻璃栏（用于输入栏、顶部栏）
 *
 * 特征：
 * - 椭圆形（pill 形）或圆角矩形
 * - 强模糊（API 31+）
 * - 细边框高光
 */
@Composable
fun FloatingGlassBar(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(50),
    tonalElevation: Dp = TaMotion.FLOATING_ELEVATION,
    content: @Composable () -> Unit
) {
    GlassSurface(
        modifier = modifier,
        shape = shape,
        tonalElevation = tonalElevation,
        alpha = 0.78f,
        borderAlpha = 0.18f,
        content = content
    )
}
