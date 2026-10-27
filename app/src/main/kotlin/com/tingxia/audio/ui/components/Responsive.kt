package com.tingxia.audio.ui.components

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 响应式布局的公共件（Material 3 指南 R3.1 / R3.4 / R3.5）。
 *
 * ## 为什么需要它
 *
 * 2026-10-27 的审计发现：全仓**没有任何窗口尺寸判断** —— 列表与正文一律
 * `fillMaxWidth` + 固定 16dp 边距 ⇒ 在平板、折叠屏展开态、横屏下内容
 * 满宽铺开，单行正文能拉到 1000+dp 字符宽，阅读行长失控。
 *
 * Material 3 的栅格规范给出的答案是 **WindowSizeClass**：
 *
 * | 尺寸类 | 宽度 | 边距 | 栏数 |
 * |---|---|---|---|
 * | Compact  | <600dp  | 16dp | 4 |
 * | Medium   | 600-839dp | 24dp | 8 |
 * | Expanded | ≥840dp | 24dp | 12 |
 *
 * ## 本 App 的落地口径
 *
 * · Compact（手机竖屏）：维持现状 16dp 边距，**不动** —— 改了就等于改版。
 * · Medium / Expanded：内容加**最大宽度上限**并居中，
 *   上限取 Material 3 建议的 ~840dp（R3.4「expanded 宽度下不要满宽」）。
 *
 * ## 为什么用 WindowSizeClass 而不是自己判断 dp 断点
 *
 * 指南 R3.2 明确「不要用固定像素断点」：设备类别是流动的（折叠屏展开、
 * 分屏、桌面模式窗口）。WindowSizeClass 由系统按**可用窗口宽度**判定，
 * 分屏时也会变 —— 比自己写 `if (width > 600.dp)` 准。
 */
object Responsive {

    /**
     * 宽屏下的最大内容宽度。
     *
     * 840dp = Material 3 建议值（R3.4）。再宽正文就没法读了 ——
     * 中文一行超过 ~40 汉字就开始跳行，眼睛找行首会累。
     */
    val MaxContentWidth = 840.dp

    /** 中等及以上宽度用的边距（Material 3 栅格：24dp）。 */
    val WideHorizontalPadding = 24.dp
}

/**
 * 当前窗口的宽度尺寸类。
 *
 * 取不到 Activity 时（少见，比如在 Preview 里）回落 Compact ——
 * 那是最保守的一档（16dp 边距、满宽），不会出错。
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberWidthSizeClass(): WindowWidthSizeClass {
    val activity = LocalContext.current as? Activity ?: return WindowWidthSizeClass.Compact
    return calculateWindowSizeClass(activity).widthSizeClass
}

/**
 * 给内容加**最大宽度上限**并居中（宽屏时）。
 *
 * 用法：`Modifier.contentWidthLimit()` 直接加在列表/正文的 Modifier 链上。
 *
 * ⚠️ 为什么不在这里加水平边距：边距由各屏自己定（Compact 16dp），
 * 这里只负责"别铺得太宽"，两件事分开才不会互相打架。
 *
 * ⚠️ **居中靠调用方的容器**：`widthIn(max=)` 只限制宽度，不负责摆位置。
 * 在 `Column`（默认 `Alignment.Start`）里，子项被限宽后会**靠左**，
 * 右边空出一大块 —— 看起来像布局坏了。要真正居中，用
 * [ResponsiveContent] 包一层（它自带居中容器）。
 */
@Composable
fun Modifier.contentWidthLimit(): Modifier {
    val widthClass = rememberWidthSizeClass()
    return if (widthClass == WindowWidthSizeClass.Expanded) {
        this.widthIn(max = Responsive.MaxContentWidth)
    } else {
        this
    }
}

/**
 * 宽屏下把内容限宽并**居中**（R3.4）。
 *
 * 用法：把原来那个 `fillMaxSize()` 的根容器换成它：
 *
 * ```kotlin
 * ResponsiveContent(modifier = Modifier.fillMaxSize()) { inner ->
 *     LazyColumn(contentPadding = inner) { ... }
 * }
 * ```
 *
 * ## 为什么不能只靠 `contentWidthLimit()`
 *
 * `widthIn(max = 840.dp)` 只改**尺寸**，不改**位置**。
 * 在 `Column`/`Box` 的默认对齐（Start）下，限宽后的内容会贴左边，
 * 右边留一大片空白 —— 屏幕宽了反而更难看。
 * 这里用「外层满宽 + 内容层限宽 + contentAlignment = TopCenter」
 * 两层嵌套把居中这件事做实。
 *
 * Compact 下 `widthIn(max=840)` 不生效（本来就不到 840dp），
 * 所以效果和现在完全一致 —— 手机用户看不到任何变化。
 */
@Composable
fun ResponsiveContent(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val widthClass = rememberWidthSizeClass()
    val maxWidth = if (widthClass == WindowWidthSizeClass.Expanded) {
        Responsive.MaxContentWidth
    } else {
        // Compact / Medium：不限宽（Medium 上限 839dp，本来也不到 840）。
        Dp.Unspecified
    }
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        // ⚠️ **顺序不能反**：`widthIn` 必须在 `fillMaxWidth` **之前**。
        //
        // 约束是从外往里传的，链上靠后的修饰符看到的是靠前修饰符改过的约束：
        // · `.fillMaxWidth().widthIn(max = 840)` —— fillMaxWidth 先把
        //   minWidth/maxWidth 都钉成父宽（1000），widthIn 再走
        //   `constrainWidth(840)` = `840.coerceIn(1000, 1000)` = **1000**，
        //   上限被自己的下限夹没了 ⇒ 限宽**静默失效**。
        // · `.widthIn(max = 840).fillMaxWidth()` —— 先把可用宽度收到 840，
        //   fillMaxWidth 再在 840 里取满 ⇒ 宽度正好 840。
        //
        // 这条不是理论推导：`ResponsiveContentTest` 在写反的版本下
        // 测出「宽屏下内容宽度=1000px 不应超过上限=840px」，
        // 护栏当场把方向纠了回来。
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth(),
            content = content,
        )
    }
}
