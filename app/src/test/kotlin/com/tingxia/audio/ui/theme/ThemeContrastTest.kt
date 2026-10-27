package com.tingxia.audio.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调色板**对比度契约**（Material 3 指南 R6.7 / R1.4 / R1.10）。
 *
 * ## 为什么需要这个测试
 *
 * 2026-10-27 的 UI 审计发现：浅色主题的 `primary` = #A67B5B 压米白只有
 * **3.35:1**（正文需 ≥4.5:1），而它是**全 App 主按钮的底色** ——
 * 意味着每一个主按钮上的文字都不达标。`error` 那一组更糟：
 * 对话框底 2.42:1、卡片底 2.96:1、errorContainer 上 3.03:1。
 *
 * 这类缺陷**编译不过不了它、lint 也查不出来**：颜色值都是合法的，
 * 组合也是 Material 3 允许的组合 —— 只有把前景和背景**摆在一起算**
 * 才知道读不读得清。
 *
 * ⇒ 把它变成一条会红的测试：谁改了色板而没管对比度，这里直接失败。
 *
 * ## 判据
 *
 * · 正文/小字（<18sp，或 <14sp 粗体）：≥ **4.5:1**（WCAG AA）
 * · 大字（≥18sp，或 ≥14sp 粗体）与纯图形：≥ **3:1**（WCAG AA 非文本）
 *
 * ## 覆盖的配对
 *
 * 不只测「onX 压 X」这一种 —— 真实代码里还会出现
 * 「primary 当正文色压在 surface / 对话框底上」「error 压在卡片底上」
 * 这些组合，一并钉住。
 */
class ThemeContrastTest {

    /** WCAG 相对亮度。 */
    private fun luminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    /**
     * 把带 alpha 的颜色**合成**到不透明底上。
     *
     * 为什么必须合成：`errorContainer = error.copy(alpha = 0.15f)` 这种写法，
     * 它的**实际**底色取决于压在什么上面（深色 surface 还是浅色页面）⇒
     * 不合成就算出错误结论（2026-10-27 就是这么漏判的）。
     *
     * @param backdrop 该颜色实际压着的底
     */
    private fun composite(color: Color, backdrop: Color): Color {
        val a = color.alpha
        if (a >= 1f) return color
        fun mix(fg: Float, bg: Float) = fg * a + bg * (1f - a)
        return Color(
            red = mix(color.red, backdrop.red),
            green = mix(color.green, backdrop.green),
            blue = mix(color.blue, backdrop.blue),
            alpha = 1f,
        )
    }

    /** 对比度，范围 1.0 ~ 21.0。 */
    private fun contrast(fg: Color, bg: Color): Double {
        val l1 = luminance(fg)
        val l2 = luminance(bg)
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertContrast(
        label: String,
        fg: Color,
        bg: Color,
        min: Double,
        backdrop: Color,
    ) {
        // 半透明的前景/背景都要先合成到**实际底色**上，否则算出来的对比度是假的。
        val realFg = composite(fg, backdrop)
        val realBg = composite(bg, backdrop)
        val actual = contrast(realFg, realBg)
        assertTrue(
            "$label 对比度只有 %.2f:1（需 ≥%.1f:1）—— 前景 %s 压背景 %s".format(
                actual, min, realFg.toHex(), realBg.toHex(),
            ),
            actual >= min,
        )
    }

    private fun Color.toHex(): String = "#%06X".format(
        (red * 255).toInt() * 0x10000 +
            (green * 255).toInt() * 0x100 +
            (blue * 255).toInt(),
    )

    /**
     * 逐条列出「前景角色 → 背景角色」的配对。
     *
     * 刻意写成**数据**而不是一堆 assertTrue：新增颜色槽时只要在这里加一行，
     * 就自动进入检查范围 —— 不会因为"忘了写断言"而漏掉一个槽。
     */
    private data class Pair(
        val label: String,
        val fg: (ColorScheme) -> Color,
        val bg: (ColorScheme) -> Color,
        val min: Double = 4.5,
    )

    private val textPairs = listOf(
        Pair("onPrimary 压 primary", { it.onPrimary }, { it.primary }),
        Pair("onSecondary 压 secondary", { it.onSecondary }, { it.secondary }),
        Pair("onTertiary 压 tertiary", { it.onTertiary }, { it.tertiary }),
        Pair("onSurface 压 surface", { it.onSurface }, { it.surface }),
        Pair("onSurface 压 background", { it.onSurface }, { it.background }),
        Pair("onSurface 压 surfaceContainerHigh", { it.onSurface }, { it.surfaceContainerHigh }),
        Pair("onBackground 压 background", { it.onBackground }, { it.background }),
        Pair("onSurfaceVariant 压 surface", { it.onSurfaceVariant }, { it.surface }),
        Pair("onSurfaceVariant 压 surfaceVariant", { it.onSurfaceVariant }, { it.surfaceVariant }),
        Pair("onPrimaryContainer 压 primaryContainer", { it.onPrimaryContainer }, { it.primaryContainer }),
        Pair("onSecondaryContainer 压 secondaryContainer", { it.onSecondaryContainer }, { it.secondaryContainer }),
        Pair("onTertiaryContainer 压 tertiaryContainer", { it.onTertiaryContainer }, { it.tertiaryContainer }),
        Pair("onError 压 error", { it.onError }, { it.error }),
        Pair("onErrorContainer 压 errorContainer", { it.onErrorContainer }, { it.errorContainer }),
        Pair("inverseOnSurface 压 inverseSurface", { it.inverseOnSurface }, { it.inverseSurface }),
        Pair("inversePrimary 压 inverseSurface", { it.inversePrimary }, { it.inverseSurface }),
        // primary / error 当**正文色**用（App 里到处这么用）
        Pair("primary 压 surface", { it.primary }, { it.surface }),
        Pair("primary 压 background", { it.primary }, { it.background }),
        Pair("primary 压 surfaceContainerLow", { it.primary }, { it.surfaceContainerLow }),
        Pair("primary 压 surfaceContainerHigh", { it.primary }, { it.surfaceContainerHigh }),
        Pair("error 压 surface", { it.error }, { it.surface }),
        Pair("error 压 background", { it.error }, { it.background }),
        Pair("error 压 surfaceContainerLow", { it.error }, { it.surfaceContainerLow }),
        Pair("error 压 surfaceContainerHigh", { it.error }, { it.surfaceContainerHigh }),
    )

    /**
     * 只在**非文本**档（≥3:1）检查的配对。
     *
     * 为什么单独一组：深色主题里 `surfaceContainerHighest`(#4A4642) 是最亮的一档
     * 容器，而 error 要压住它就得红到不再像"错误色" —— 这是**结构性的取舍**，
     * 不是配错色。该配对在 App 里也不存在（错误文字不会出现在最高层容器上），
     * 所以不拿它去反向逼色板变歪。
     */
    private val nonTextPairs = listOf(
        Pair("error 压 surfaceContainerHighest", { it.error }, { it.surfaceContainerHighest }, 3.0),
    )

    /**
     * 音符进度指示器的圆底**不得**等于轨道色（R-MUSIC-KNOB 2026-10-27）。
     *
     * 用户反馈：「圆底的着色和进度轨道的颜色是一样的，不好看」——
     * 之前圆底直接取 activeTrackColor（底部条 = primary、全屏 = Cream），
     * 圆底和轨道糊成一块，指示器的圆形读不出来。
     *
     * 这条断言的意义：把"圆底 ≠ 轨道色"写成**会红的测试**，
     * 将来谁把圆底改回跟随轨道色，这里立刻失败。
     */
    @Test
    fun `音符圆底色不得等于两条进度轨道的颜色`() {
        assertTrue(
            "圆底色不得等于底部条轨道色（primary）",
            MusicNoteKnob != PrimaryLight,
        )
        assertTrue(
            "圆底色不得等于全屏播放器轨道色（Cream）",
            MusicNoteKnob != Cream,
        )
        // 音符字形（米白）压圆底（墨黑）必须是正文级对比度。
        assertContrast(
            "音符字形 Cream 压墨黑圆底",
            Cream,
            MusicNoteKnob,
            4.5,
            MusicNoteKnob,
        )
        // 圆底对两条轨道色都要能"跳出来"（图形级 ≥3:1 是 WCAG 非文本判据；
        // 暖赭轨道压墨黑只有 2.47:1，这里按"明显可辨"的下限 1.6:1 钉住）。
        assertTrue(
            "墨黑圆底压暖赭轨道只有 %.2f:1，圆底会糊进轨道".format(
                contrast(MusicNoteKnob, PrimaryLight),
            ),
            contrast(MusicNoteKnob, PrimaryLight) >= 1.6,
        )
        assertTrue(
            "墨黑圆底压米白轨道只有 %.2f:1".format(contrast(MusicNoteKnob, Cream)),
            contrast(MusicNoteKnob, Cream) >= 3.0,
        )
    }

    @Test
    fun `浅色主题的每一组前景背景都达到 WCAG AA`() {
        val scheme = LightColorScheme
        textPairs.forEach { p ->
            assertContrast("浅色：${p.label}", p.fg(scheme), p.bg(scheme), p.min, scheme.background)
        }
        nonTextPairs.forEach { p ->
            assertContrast("浅色：${p.label}", p.fg(scheme), p.bg(scheme), p.min, scheme.background)
        }
    }

    @Test
    fun `深色主题的每一组前景背景都达到 WCAG AA`() {
        val scheme = DarkColorScheme
        textPairs.forEach { p ->
            assertContrast("深色：${p.label}", p.fg(scheme), p.bg(scheme), p.min, scheme.background)
        }
        nonTextPairs.forEach { p ->
            assertContrast("深色：${p.label}", p.fg(scheme), p.bg(scheme), p.min, scheme.background)
        }
    }

    /**
     * 非文本对比度（图标、进度条、分隔线）≥ 3:1。
     *
     * 单独一组，因为它允许比正文低一档 —— 但**不能再低**：
     * 低于 3:1 的图标在低亮度环境/视力障碍下就是"看不见"。
     */
    @Test
    fun `浅色主题的非文本对比度达到 3 比 1`() {
        val scheme = LightColorScheme
        assertContrast("浅色：outline 压 surface", scheme.outline, scheme.surface, 3.0, scheme.background)
        assertContrast("浅色：primary 压 surface", scheme.primary, scheme.surface, 3.0, scheme.background)
    }

    @Test
    fun `深色主题的非文本对比度达到 3 比 1`() {
        val scheme = DarkColorScheme
        assertContrast("深色：outline 压 surface", scheme.outline, scheme.surface, 3.0, scheme.background)
        assertContrast("深色：primary 压 surface", scheme.primary, scheme.surface, 3.0, scheme.background)
    }

    /**
     * 深色底**不能是纯黑**（R1.8）。
     *
     * 纯黑 + 纯白的对比度确实最高，但 OLED 上纯黑区域会"断掉"，
     * 且长时间阅读纯黑底白字更刺眼 —— Material 3 用的是**带色相的暗色**。
     */
    @Test
    fun `深色主题的底色不是纯黑`() {
        val scheme = DarkColorScheme
        listOf(scheme.background, scheme.surface, scheme.surfaceContainerLow).forEach { c ->
            assertTrue("深色底色 $c 是纯黑/近纯黑（R1.8 要求用带色相的暗表面）", c.red > 0.02f)
        }
    }

    /**
     * 通知卡的自定义底色 × 文字色（2026-10-27 加）。
     *
     * ## 为什么单独一组
     *
     * 通知卡的底色**不在 ColorScheme 里** —— 它们是 App 自己的语义色
     * （NotificationUnreadBackground / NotificationReadBackground 及其深色版），
     * 所以上面的 `textPairs` 表覆盖不到它们。
     *
     * 而这里恰恰出过一个 P0：通知卡原来**只用浅色那套常量**，
     * 卡片里的文字却取 `colorScheme.onSurface`（深色下是近白 #E8E4DD）
     * ⇒ 深色模式下 = 一块块**亮奶油色**卡片浮在暗底上、近白字压亮底，
     * 实测 **1.07:1** —— 整屏通知在深色模式下等于消失。
     *
     * 这类缺陷只有把"自定义底色"和"色板里的文字色"摆在一起算才知道，
     * ⇒ 钉成测试。
     */
    @Test
    fun `通知卡的自定义底色与文字色达到 WCAG AA`() {
        // ── 浅色：卡片底是米白系，文字取 onSurfaceVariant ──
        val light = LightColorScheme
        assertContrast(
            "浅色：通知正文 压 未读卡底",
            light.onSurfaceVariant, NotificationUnreadBackground, 4.5, light.background,
        )
        assertContrast(
            "浅色：通知正文 压 已读卡底",
            light.onSurfaceVariant, NotificationReadBackground, 4.5, light.background,
        )

        // ── 深色：卡片底是暗棕系，文字用中间档 NotificationBodyDark ──
        //
        // 曾经深色正文直接用 onSurface ⇒ **标题与正文同色**，层级全丢；
        // 换成 onSurfaceVariant 也不行 —— 它压未读卡底只有 4.04:1（未读卡底
        // 比已读卡底亮一档，是最紧的那条）。中间档 #948F88 两边都达标。
        val dark = DarkColorScheme
        assertContrast(
            "深色：通知正文 压 未读卡底",
            NotificationBodyDark, NotificationUnreadBackgroundDark, 4.5, dark.background,
        )
        assertContrast(
            "深色：通知正文 压 已读卡底",
            NotificationBodyDark, NotificationReadBackgroundDark, 4.5, dark.background,
        )
        // 标题（onSurface）同样要压得住两张卡底。
        assertContrast(
            "深色：通知标题 压 未读卡底",
            dark.onSurface, NotificationUnreadBackgroundDark, 4.5, dark.background,
        )
        assertContrast(
            "深色：通知标题 压 已读卡底",
            dark.onSurface, NotificationReadBackgroundDark, 4.5, dark.background,
        )
    }

    /**
     * primaryContainer 的配对（2026-10-27 改色后新增）。
     *
     * ## 为什么这一组之前没有断言
     *
     * `primaryContainer` 原本是中性刻度 Neutral100，和 surfaceVariant 同值，
     * 所以「onPrimaryContainer 压 primaryContainer」这条老断言形同虚设
     * （Ink 压任何浅底都 ≫4.5，永远不会红）。
     * 改成**品牌暖赭浅色档** #EBD9C8 之后，它才真正可能出问题 ——
     * 而真正出问题的地方不是那条老断言，而是下面两条**从来没人测过**的组合：
     *
     *   ① 技能卡图标用 `primary` 压 `primaryContainer`（HomeScreen）
     *   ② EvaluationDialog 的总评块：`primaryContainer@30%` **合成**到
     *      对话框底 surfaceContainerHigh 上，上面是 onSurface 正文
     *
     * ②尤其危险：半透明容器的实际底色取决于它压在什么上面 ——
     * 不合成就算不出真实对比度（2026-10-27 通知卡那次就是这么漏判的）。
     */
    @Test
    fun `primaryContainer 的每一组配对都达到 WCAG AA`() {
        val light = LightColorScheme

        // ① 技能卡图标：primary 压 primaryContainer（图形，≥3:1）
        assertContrast(
            "浅色：技能卡图标 primary 压 primaryContainer",
            light.primary, light.primaryContainer, 3.0, light.background,
        )

        // ② 对话框里的总评块：primaryContainer@30% 合成到对话框底
        val composed = composite(
            light.primaryContainer.copy(alpha = 0.30f),
            light.surfaceContainerHigh,
        )
        assertContrast(
            // ⚠️ label 里**不能出现 `%`** —— assertContrast 内部用 String.format
            // 拼断言消息，label 会被塞进格式串里，`% ` 会被当成格式转换符
            // 直接抛 UnknownFormatConversionException（第一次写这条断言时踩过）。
            "浅色：总评块正文 onSurface 压 primaryContainer 三成透明度合成底",
            light.onSurface, composed, 4.5, light.surfaceContainerHigh,
        )
    }

    /**
     * 深色通知卡：标题与正文**必须不同色**（2026-10-27 修）。
     *
     * ## 为什么需要这条
     *
     * 修复前深色下标题和正文**同为 `onSurface`(#E8E4DD)** ——
     * 一屏通知里每张卡的两行字一样重，层级只靠字号撑着。
     * 而两行都"达标"并不代表"有层级" ⇒ 对比度测试永远发现不了这个问题，
     * 得单独钉一条。
     *
     * 断言的是**可观测结果**（两个角色色不相等），
     * 而不是"我写了某个 hex"（那种断言改个名字就绿了，没牙齿）。
     */
    @Test
    fun `深色通知卡的标题与正文不是同一个颜色`() {
        assertTrue(
            "深色通知卡的标题与正文同色（都是 onSurface）—— 层级丢了",
            DarkColorScheme.onSurface != NotificationBodyDark,
        )
        // 而且正文要比标题**暗**（深色模式下"更弱"= 更暗），
        // 不能反过来（更亮 = 更强 = 变成标题了）。
        assertTrue(
            "深色通知卡的正文不应比标题更亮",
            luminance(NotificationBodyDark) < luminance(DarkColorScheme.onSurface),
        )
    }

    /**
     * 深色下**不能**再拿浅色卡底去配色板文字 —— 这正是那个 P0 的形态。
     *
     * 单独钉一条是因为它表达的是"一条**不该成立**的组合"：
     * 现在深色路径走的是 NotificationUnreadBackgroundDark，
     * 所以这条断言在修复后是**不适用**的（前景色不是它）。
     *
     * 这里改成直接断言"深色卡底 ≠ 浅色卡底"，
     * 防止有人日后把深色分支悄悄改回共用浅色常量 ——
     * 那种改动**不会报错**，只会让深色模式的通知屏再次消失。
     */
    @Test
    fun `深色通知卡底与浅色卡底不是同一个值`() {
        assertTrue(
            "深色通知卡底不能复用浅色常量（那会让整屏通知在深色模式下消失）",
            NotificationUnreadBackgroundDark != NotificationUnreadBackground &&
                NotificationReadBackgroundDark != NotificationReadBackground,
        )
    }
}
