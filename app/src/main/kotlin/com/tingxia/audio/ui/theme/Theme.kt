package com.tingxia.audio.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 听匣 Theme
 *
 * 视觉气质：安静 / 留白 / 印刷感 / 工具感
 * - 不使用 Material 3 默认紫
 * - 使用暖色调品牌色
 * - 暗色模式使用暖深色 (#1A1614) 而非纯黑
 */

private val LightColorScheme = lightColorScheme(
    // Brand
    primary = WarmOchre,
    onPrimary = Cream,
    primaryContainer = Neutral100,
    onPrimaryContainer = Ink,

    secondary = Neutral600,
    onSecondary = Cream,
    secondaryContainer = Neutral100,
    onSecondaryContainer = Neutral700,

    tertiary = WarmOchre,
    onTertiary = Cream,

    // Surface & Background — 使用米白而非纯白
    background = Cream,
    onBackground = Ink,
    surface = Neutral50,
    onSurface = Ink,
    surfaceVariant = Neutral100,
    onSurfaceVariant = Neutral600,

    // Outline — 使用暖灰而非冷灰
    outline = Neutral300,
    outlineVariant = Neutral200,

    // Error
    error = Error,
    onError = Cream,
    errorContainer = Error.copy(alpha = 0.1f),
    onErrorContainer = Error,

    // Inverse
    inverseSurface = Neutral800,
    inverseOnSurface = DarkText,
    inversePrimary = WarmOchre,
)

private val DarkColorScheme = darkColorScheme(
    // Brand
    primary = WarmOchre,
    onPrimary = Neutral900,
    primaryContainer = Neutral700,
    onPrimaryContainer = Neutral100,

    secondary = Neutral400,
    onSecondary = Neutral900,
    secondaryContainer = Neutral700,
    onSecondaryContainer = Neutral200,

    tertiary = WarmOchre,
    onTertiary = Neutral900,

    // Surface & Background — 暖深色而非纯黑
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = Neutral800,
    onSurfaceVariant = Neutral400,

    // Outline
    outline = DarkBorder,
    outlineVariant = Neutral700,

    // Error
    error = Error,
    onError = Neutral900,
    errorContainer = Error.copy(alpha = 0.15f),
    onErrorContainer = Error,

    // Inverse
    inverseSurface = Neutral200,
    inverseOnSurface = Ink,
    inversePrimary = WarmOchre,
)

@Composable
fun TingxiaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 不使用动态色，固定使用品牌色
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
