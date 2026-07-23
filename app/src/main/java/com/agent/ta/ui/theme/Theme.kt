package com.agent.ta.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Misty Lake Light 雾湖青调浅色方案
 *
 * 25+ M3 color roles 全套覆盖，告别 Android Studio 模板的 Purple40
 */
private val MistyLakeLightColorScheme = lightColorScheme(
    primary = md_primary_light,
    onPrimary = md_onPrimary_light,
    primaryContainer = md_primaryContainer_light,
    onPrimaryContainer = md_onPrimaryContainer_light,
    secondary = md_secondary_light,
    onSecondary = md_onSecondary_light,
    secondaryContainer = md_secondaryContainer_light,
    onSecondaryContainer = md_onSecondaryContainer_light,
    tertiary = md_tertiary_light,
    onTertiary = md_onTertiary_light,
    tertiaryContainer = md_tertiaryContainer_light,
    onTertiaryContainer = md_onTertiaryContainer_light,
    error = md_error_light,
    onError = md_onError_light,
    errorContainer = md_errorContainer_light,
    onErrorContainer = md_errorContainer_light,
    background = md_background_light,
    onBackground = md_onBackground_light,
    surface = md_surface_light,
    onSurface = md_onSurface_light,
    surfaceVariant = md_surfaceVariant_light,
    onSurfaceVariant = md_onSurfaceVariant_light,
    surfaceContainerLowest = md_surfaceContainerLowest_light,
    surfaceContainerLow = md_surfaceContainerLow_light,
    surfaceContainer = md_surfaceContainer_light,
    surfaceContainerHigh = md_surfaceContainerHigh_light,
    surfaceContainerHighest = md_surfaceContainerHighest_light,
    outline = md_outline_light,
    outlineVariant = md_outlineVariant_light,
    scrim = md_scrim_light,
    inverseSurface = md_inverseSurface_light,
    inverseOnSurface = md_inverseOnSurface_light,
    inversePrimary = md_inversePrimary_light
)

/**
 * Misty Lake Dark 雾湖青调深色方案
 */
private val MistyLakeDarkColorScheme = darkColorScheme(
    primary = md_primary_dark,
    onPrimary = md_onPrimary_dark,
    primaryContainer = md_primaryContainer_dark,
    onPrimaryContainer = md_onPrimaryContainer_dark,
    secondary = md_secondary_dark,
    onSecondary = md_onSecondary_dark,
    secondaryContainer = md_secondaryContainer_dark,
    onSecondaryContainer = md_onSecondaryContainer_dark,
    tertiary = md_tertiary_dark,
    onTertiary = md_onTertiary_dark,
    tertiaryContainer = md_tertiaryContainer_dark,
    onTertiaryContainer = md_onTertiaryContainer_dark,
    error = md_error_dark,
    onError = md_onError_dark,
    errorContainer = md_errorContainer_dark,
    onErrorContainer = md_onErrorContainer_dark,
    background = md_background_dark,
    onBackground = md_onBackground_dark,
    surface = md_surface_dark,
    onSurface = md_onSurface_dark,
    surfaceVariant = md_surfaceVariant_dark,
    onSurfaceVariant = md_onSurfaceVariant_dark,
    surfaceContainerLowest = md_surfaceContainerLowest_dark,
    surfaceContainerLow = md_surfaceContainerLow_dark,
    surfaceContainer = md_surfaceContainer_dark,
    surfaceContainerHigh = md_surfaceContainerHigh_dark,
    surfaceContainerHighest = md_surfaceContainerHighest_dark,
    outline = md_outline_dark,
    outlineVariant = md_outlineVariant_dark,
    scrim = md_scrim_dark,
    inverseSurface = md_inverseSurface_dark,
    inverseOnSurface = md_inverseOnSurface_dark,
    inversePrimary = md_inversePrimary_dark
)

/**
 * TA App 主题
 *
 * 设计目标：温暖陪伴系 Branded Material 3
 * - 完整 M3 color scheme（25+ 槽位）全套品牌色覆盖
 * - 默认关闭 dynamicColor，确保品牌色为主导
 * - 用户主动开启 dynamicColor 时才跟随壁纸（Android 12+）
 * - M3E spring physics 通过 [TaMotion] 工具在关键动效处手动注入
 *   （因 stable material3 1.4.x 中 MaterialExpressiveTheme 仍是 internal，
 *    等未来 GA 后可直接切到 MaterialExpressiveTheme）
 *
 * @param dynamicColor 是否跟随系统壁纸（默认 false，保留品牌色）
 */
@Composable
fun TaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> MistyLakeDarkColorScheme
        else -> MistyLakeLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TaTypography,
        shapes = TaShapes,
        content = content
    )
}
