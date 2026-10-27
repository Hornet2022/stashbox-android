package com.tingxia.audio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 听匣 Theme
 *
 * 视觉气质：安静 / 留白 / 印刷感 / 工具感
 * - 不使用 Material 3 默认紫
 * - 使用暖色调品牌色
 * - 暗色模式使用暖深色 (#1A1614) 而非纯黑
 */

/** 浅色方案。internal：给 ThemeContrastTest 用（单元测试与主源码同模块，internal 可见）。 */
internal val LightColorScheme = lightColorScheme(
    // Brand
    //
    // ⚠️ primary 换成了 PrimaryLight（品牌暖赭 tone 45）而不是品牌主色本身 ——
    // 原因见 Color.kt 里「浅色主题的色调阶」那段：原值 #A67B5B 压米白只有
    // 3.35:1，而它是**全 App 主按钮的底色**，等于每个主按钮的文字都不达标。
    // 色相不变（同一个暖赭家族），只是压深到 WCAG AA 达标。
    primary = PrimaryLight,
    onPrimary = Cream,
    // 2026-10-27：Neutral100 → 品牌暖赭浅色档。
    //
    // 原来这里用中性刻度 ⇒ 所有"品牌色容器"（技能卡、标签筛选条）
    // 其实都是**灰**的，主色的暖赭只出现在文字/图标上，没有一处底是品牌色。
    // 改成 primary 的同色相浅色档后，首页的暖赭感回来了。
    // 配对数值见 Color.kt::PrimaryContainerLight 与 ThemeContrastTest。
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = Ink,

    secondary = Neutral600,
    onSecondary = Cream,
    secondaryContainer = Neutral100,
    onSecondaryContainer = Neutral700,

    tertiary = PrimaryLight,
    onTertiary = Cream,

    // Surface & Background — 使用米白而非纯白
    background = Cream,
    onBackground = Ink,
    surface = Neutral50,
    onSurface = Ink,
    surfaceVariant = Neutral100,
    onSurfaceVariant = Neutral600,

    // Outline
    //
    // ⚠️ outline 从 Neutral300(#B8B2A8) 改成 Neutral500(#6A665F)：
    // 原值压米白只有 1.88:1，而 outline 是**输入框边框、开关轨道、选中框**
    // 这类"控件本体"的颜色 —— 低于 3:1 时低视力用户根本看不出这是可点的框。
    // 装饰性分隔线用 outlineVariant（保持浅），两者分工明确。
    outline = Neutral500,
    outlineVariant = Neutral200,

    // Error
    //
    // ⚠️ 换成 ErrorLight（tone 40）+ 不透明 errorContainer + 更深的
    // onErrorContainer。原来的组合有三个问题：
    //   ① error #B87070 压对话框底 surfaceContainerHigh #D4CFC6 = 2.42:1
    //   ② errorContainer 是 error@10% 的**半透明**色 ⇒ 实际底色随底下是什么
    //      而变，对比度根本不可控（实测 3.03:1）
    //   ③ onErrorContainer 直接用了 error 本身（3.03:1）—— 容器色和它上面的
    //      文字用同一个值，等于没配对
    error = ErrorLight,
    onError = Cream,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,

    // Inverse
    inverseSurface = Neutral800,
    inverseOnSurface = DarkText,
    inversePrimary = InversePrimaryLight,

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

/** 深色方案。internal：给 ThemeContrastTest 用。 */
internal val DarkColorScheme = darkColorScheme(
    // Brand
    //
    // primary 用 PrimaryDark（品牌暖赭 tone 70，比浅色侧亮不少）：
    // 深色主题里 primary **既当按钮底色又当正文色**，而 M3 的对话框底
    // （surfaceContainerHigh）比页面底亮一档 ⇒ primary 必须同时压得住三种底。
    // 数值见 Color.kt 里 PrimaryDark 的注释；onPrimary 换成暖深棕（tone 30）。
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = Neutral700,
    onPrimaryContainer = Neutral100,

    secondary = Neutral400,
    onSecondary = Neutral900,
    secondaryContainer = Neutral700,
    onSecondaryContainer = Neutral200,

    tertiary = PrimaryDark,
    onTertiary = OnPrimaryDark,

    // Surface & Background — 暖深色而非纯黑
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = Neutral800,
    onSurfaceVariant = Neutral400,

    // Outline
    //
    // ⚠️ 从 DarkBorder(#3A352E) 改成 Neutral400(#8C8680)：原值压深色 surface
    // 只有 1.33:1 —— 深色模式下的输入框/开关边框几乎看不见。
    outline = Neutral400,
    outlineVariant = Neutral700,

    // Error
    //
    // 深色侧的主色不用动（#A67B5B 压 DarkBg = 4.78:1 已达标），
    // 但两处必须改：
    // ① onErrorContainer 换成**浅色**（tone 80）—— 原来的 error@15% 底上
    //    压着同色的 error 文字只有 3.96:1，换成浅色后 8.28:1。
    // ② errorContainer 改成**不透明** —— 半透明容器的实际底色取决于它压在
    //    什么上面（这里是深色 surface），对比度就不可控了；M3 的
    //    errorContainer 本身就是不透明的一档。
    //
    // error 本身也换成了 ErrorDark（原值压深色 surface 只有 4.31:1），
    // 详见 Color.kt 里 ErrorDark 的注释。
    error = ErrorDark,
    onError = Neutral900,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,

    // Inverse
    inverseSurface = Neutral200,
    inverseOnSurface = Ink,
    inversePrimary = Neutral800,

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
 *
 * ## 主题模式（2026-10-27，Material 3 指南 R1.9）
 *
 * 之前只有「跟随系统」一种。现在支持「跟随系统 / 浅色 / 深色」三选一，
 * 由 [ThemeController] 持有并持久化。
 *
 * ⚠️ **不启用动态色（Material You）** —— 这是**有意为之**，不是漏做：
 * 听匣有确定的品牌视觉（暖赭 + 米白，纸质印刷感），而动态色会把整个 App
 * 染成用户壁纸的颜色，品牌识别直接消失。用户要的是"听匣"，不是"我的壁纸"。
 * 深浅两套配色都已按 Material 3 的 38 个 color role 完整定义（不是取反凑数），
 * 暗色用暖深色 #1A1614 而非纯黑（R1.8）。
 */
@Composable
fun TingxiaTheme(
    themeController: ThemeController = rememberThemeController(),
    content: @Composable () -> Unit
) {
    val mode by themeController.mode.collectAsStateWithLifecycle()
    val darkTheme = resolveDarkTheme(mode, isSystemInDarkTheme())

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

