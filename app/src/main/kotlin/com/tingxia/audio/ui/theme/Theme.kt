package com.tingxia.audio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

    // ── M3 1.1+ 新增的容器色槽（2026-10-03 补齐）────────────────────
    // ColorScheme 一共 38 个 role，之前只设了 25 个。没设的那些会**静默
    // 落回 Material 3 基线调色板** —— 也就是那条薰衣草紫。
    // 而 surfaceContainer* / surfaceTint 恰恰是组件库内部真正在读的：
    //   AlertDialog / ModalBottomSheet → surfaceContainerHigh / Low
    //   DropdownMenu                   → surfaceContainer
    //   ElevatedCard                  → surfaceContainerLow
    //   滚动后的 TopAppBar             → surfaceContainer（scrolledContainerColor）
    //   卡片色调叠加（tonal elevation） → surfaceTint
    // 结果就是：页面底色是米白，卡片 / 弹窗 / 菜单 / sheet 是冷紫白。
    // 这和 Color.kt 早就记录并修掉的那类问题（「在米白底上显出偏色，
    // 一张列表里能看出有的行偏冷，像图层没对齐」）是同一个毛病，
    // 只是藏在了主题层上面一层。现在按暖中性刻度补齐。
    surfaceDim = Neutral200,
    surfaceBright = Neutral50,
    surfaceContainerLowest = Neutral50,
    surfaceContainerLow = Neutral100,
    surfaceContainer = Neutral100,
    surfaceContainerHigh = Neutral200,
    surfaceContainerHighest = Neutral300,
    // 色调叠加往暖赭走，而不是基线的紫
    surfaceTint = WarmOchre,
    scrim = Color(0xFF000000),
    tertiaryContainer = Neutral100,
    onTertiaryContainer = Ink,
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

    // ── 深色下的容器色槽（2026-10-03 补齐，理由同浅色一侧）────────────
    surfaceDim = Neutral900,
    surfaceBright = Neutral700,
    surfaceContainerLowest = Neutral900,
    surfaceContainerLow = Neutral800,
    surfaceContainer = Neutral800,
    surfaceContainerHigh = Neutral700,
    surfaceContainerHighest = Neutral600,
    surfaceTint = WarmOchre,
    scrim = Color(0xFF000000),
    tertiaryContainer = Neutral700,
    onTertiaryContainer = Neutral100,
)

/**
 * 全局根容器。
 *
 * 之前根节点是 `TingxiaTheme { AuthRoot() }`，MaterialTheme 本身**不下发**
 * LocalContentColor —— 它只提供 ColorScheme / Typography / Shapes。
 * 于是任何没有被 Scaffold / Card / Surface 包住的 Text，
 * 拿到的 LocalContentColor 是 Compose 的默认值 Color.Black，
 * 在深色底（#1A1614）上就是黑字压黑底，完全看不见。
 *
 * 这里补一层 Surface，让 contentColor = onSurface 由根往下传，
 * 整棵树的默认文字颜色才有正确的深浅模式语义。
 */
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
    ) {
        Surface(
            color = colorScheme.background,
            contentColor = colorScheme.onBackground,
            content = content,
        )
    }
}

