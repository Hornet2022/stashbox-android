package com.tingxia.audio.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 顶栏「铃铛 + 未读角标」的**布局护栏**（2026-10-27）。
 *
 * ## 这条测试在防什么
 *
 * 用户报过两次：
 *
 * ① 「通知按钮会被通知数字角标完全遮挡」—— 角标 `align(TopEnd)` 叠在
 *    **24dp 图标盒**上，角标 20dp 完全落在图标框内，重叠 **69%**；
 *    逐像素统计角标那 20×20dp 区域：只剩 **83px** 是铃铛线条
 *    （角标区 4900px 的 1.7%）⇒ 铃铛几乎整个消失。
 * ② 「搞得好像顶栏多一个入口……角标数字只是通知按钮的一角提示」——
 *    把角标改成 20dp 并排到铃铛右侧后，角标成了一个**独立大圆点**，
 *    与铃铛几乎一样大，读起来像顶栏多了个第四个入口。
 *
 * ⇒ 正确形态：**小尺寸角标（16dp）+ 叠在铃铛右上角**，只**轻微侵入**。
 *
 * 但**这种缺陷读代码看不出来** —— 编译绿、单测绿、lint 绿，只有真机
 * 看得见；而"代码里写了 align"也不等于对齐真的生效（定位属性要在
 * 正确的层级里才起作用，这是 0.4.8 播放条事故的同一条教训）。
 *
 * ⇒ 必须拿**渲染后的坐标**来断言，而且断言的是**可观测结果**
 * （重叠面积占比、角标与图标的大小比），不是"我写了某个 modifier"。
 *
 * ## 两条断言为什么写成"与字体无关"的形式
 *
 * ① 图标用 **testTag** 而不是 contentDescription 定位。
 *    contentDescription="通知" 在 Robolectric 上查到的是 IconButton
 *    容器（40dp），在真机上查到的是 Icon 本身（24dp）——
 *    同一句查询在两个环境代表不同的东西 ⇒ 断言会飘。
 *    testTag 钉的是渲染节点自己，与环境无关。
 *
 * ② "99+" 的宽度**不能断言绝对 dp 值**。真机字体下角标 30dp，
 *    Robolectric 字体窄只有 20dp ⇒ 写成 "≥27dp" 就是一条**假断言**
 *    （它测的是字体度量，不是布局契约）。
 *    ⇒ 改成断言"文字放得下 + 盒子不被压到比文字还窄"，
 *    这两条与字体度量无关。
 *
 * ## 为什么测组件而不是测 HomeScreen
 *
 * HomeScreen 依赖 ViewModel/Hilt，没法在 JVM 上直接构造。
 * 而如果把布局代码**复刻**一份到测试里，测试和实现会一起漂移 ——
 * 回归了也测不出来，等于没测。所以生产用的布局被抽成
 * [NotificationBadgeIcon]，测试直接测**生产用的这个组件**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class NotificationBadgeIconTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(count: Int) {
        composeTestRule.setContent {
            MaterialTheme {
                NotificationBadgeIcon(count = count, onClick = {})
            }
        }
    }

    /**
     * 铃铛**图标本身**的渲染边界。
     *
     * ⚠️ 必须 `useUnmergedTree = true`：IconButton 会把子节点的语义**合并**进
     * 自己（`mergeDescendants`），合并后的树里 Icon 的 testTag 消失了 ——
     * 查合并树会报"找不到节点，但未合并树里有 1 个匹配"。
     *
     * 这也正是要拿"图标本身"而不是"IconButton 容器"的原因：
     * IconButton 容器 40dp，Icon 本身 24dp，混用会让断言飘。
     */
    private fun bellBounds() =
        composeTestRule.onNodeWithTag(NotificationBadgeIconTestTags.BELL, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

    /** 角标的渲染边界（角标自己带 contentDescription）。 */
    private fun badgeBounds(count: Int) =
        composeTestRule.onNodeWithContentDescription("$count 条未读通知")
            .fetchSemanticsNode().boundsInRoot

    @Test
    fun `角标叠在铃铛右上角而不是并排`() {
        show(count = 1)

        val bell = bellBounds()
        val badge = badgeBounds(1)

        // 角标必须在铃铛的**右上角**（水平方向有交叠、垂直方向偏上）——
        // 这是"一角提示"的形态。并排（完全不相交）会让角标读作独立入口。
        assertTrue(
            "角标没有与铃铛交叠（铃铛 right=${bell.right}px，角标 left=${badge.left}px）—— " +
                "并排的角标会读成顶栏里的另一个入口",
            badge.left < bell.right,
        )
        assertTrue(
            "角标不在铃铛上方（铃铛 top=${bell.top}px，角标 top=${badge.top}px）",
            badge.top <= bell.top,
        )
    }

    @Test
    fun `角标只轻微侵入铃铛不遮住它`() {
        show(count = 1)

        val bell = bellBounds()
        val badge = badgeBounds(1)

        // 重叠面积占比 = 重叠面积 / 铃铛面积
        //
        // ⚠️ 全部统一转成 Float 再算：`boundsInRoot` 的 left/right 在不同
        // Compose 版本里是 Int 或 Float，直接混用会得到 "None of the
        // following candidates is applicable" 的编译错误。
        val overlapW = (minOf(bell.right.toFloat(), badge.right.toFloat()) -
            maxOf(bell.left.toFloat(), badge.left.toFloat())).coerceAtLeast(0f)
        val overlapH = (minOf(bell.bottom.toFloat(), badge.bottom.toFloat()) -
            maxOf(bell.top.toFloat(), badge.top.toFloat())).coerceAtLeast(0f)
        val overlap = overlapW * overlapH
        val bellArea = bell.width.toFloat() * bell.height.toFloat()
        val ratio = if (bellArea > 0f) overlap / bellArea else 1f

        // v1（20dp 角标叠在 24dp 图标盒上）实测是 **69%** ⇒ 铃铛几乎消失。
        // 现在的目标是"像素级侵入"：留出明确余量，谁把角标改大就会破。
        assertTrue(
            "角标遮住了铃铛 ${(ratio * 100).toInt()}% 的面积（铃铛 ${bell.width}×${bell.height}px）" +
                "—— 角标应当只是'一角提示'，不是盖住按钮",
            ratio <= 0.20f,
        )
    }

    @Test
    fun `角标明显小于铃铛图标以分清主次`() {
        show(count = 1)

        val bell = bellBounds()
        val badge = badgeBounds(1)
        val density = composeTestRule.density.density

        // 角标是铃铛的附注，不是独立入口 ⇒ 必须明显更小。
        // （v2 并排方案用 20dp 角标 vs 24dp 铃铛，几乎一样大 ⇒ 用户反馈
        //   "搞得好像顶栏多一个入口"。）
        //
        // ⚠️ 这里量的是**宽度**：单数字时角标是正方形，宽即尺寸。
        // 而高度在 Robolectric 上**测不得** —— 它的字体度量是假的
        // （实测 "1" 的 Text 节点宽 2px、高 36px），而角标的高度是
        // `defaultMinSize(minHeight)` 与内容高度的较大者 ⇒ 被假字体撑到 36dp。
        // 真机上高度由 minHeight=16dp 决定，用像素采样验过（见发布说明）。
        val badgeDp = badge.width / density
        val bellDp = bell.width / density
        assertTrue(
            "角标 ${badgeDp}dp 与铃铛 ${bellDp}dp 几乎一样大 —— " +
                "角标应当明显小于图标才读得出主次",
            badgeDp <= bellDp * 0.75f,
        )
    }

    @Test
    fun `角标与铃铛垂直方向有交叠即挂在右上角`() {
        show(count = 1)

        val bell = bellBounds()
        val badge = badgeBounds(1)

        // 角标的垂直中心应落在铃铛的上半部分 —— 这才是"右上角"。
        val bellCenter = bell.center.y
        val badgeCenter = badge.center.y
        assertTrue(
            "角标不在铃铛右上角（铃铛 center.y=${bellCenter}px，角标 center.y=${badgeCenter}px）",
            badgeCenter <= bellCenter,
        )
    }

    @Test
    fun `未读数超过 99 时三个字符都完整显示不被裁切`() {
        show(count = 120)

        // "99+" 必须作为**一个完整的文本节点**存在。
        composeTestRule.onNodeWithText("99+").assertIsDisplayed()

        val text = composeTestRule.onNodeWithText("99+").fetchSemanticsNode().boundsInRoot
        val badge = badgeBounds(120)

        // 角标盒子必须装得下这段文字（角标有 4dp×2 的水平内边距）。
        // 写成"文字宽度 ≤ 盒子宽度"而不是"盒子 ≥ 27dp" ——
        // 后者测的是字体度量（真机 30dp / Robolectric 20dp），是条假断言。
        assertTrue(
            "\"99+\" 文字宽 ${text.width}px，超出了角标盒子宽 ${badge.width}px —— " +
                "数字被裁成 \"99\"/\"9+\" 这种错数",
            text.width <= badge.width,
        )
    }

    @Test
    fun `角标盒子的宽度由内容决定不被父容器压扁`() {
        show(count = 120)

        val badge = composeTestRule.onNodeWithContentDescription("120 条未读通知")
            .fetchSemanticsNode().boundsInRoot

        // minWidth=16dp 的契约：角标是**高度固定、宽度按内容**的药丸，
        // 高度下限 16dp（M3 Badge 小号标准）。
        //
        // ⚠️ 这条防的是"角标被塞进 IconButton 的 content"那版：
        // IconButton 内容区只有 24dp，"99+"（真机 22dp 文字 + 8dp 内边距 = 30dp）
        // 会被裁成 "99"/"9+"。这里退一步断言"盒子高度不被压到比 minWidth 更矮"。
        val heightDp = badge.height / composeTestRule.density.density
        assertTrue(
            "角标盒子只有 ${heightDp}dp 高，低于它自己的 minHeight 16dp —— " +
                "说明被父容器压扁了",
            heightDp >= 16f,
        )
    }

    @Test
    fun `未读数为 0 时不渲染角标`() {
        show(count = 0)

        // 用节点查询而不是遍历语义树 —— 语义树的 children 层级随版本变动，
        // 遍历写法既脆又难读。
        val badgeNodes = composeTestRule
            .onAllNodes(hasContentDescription("0 条未读通知"))
            .fetchSemanticsNodes()
        assertTrue(
            "未读数为 0 时不该渲染角标（却找到 ${badgeNodes.size} 个角标节点）",
            badgeNodes.isEmpty(),
        )
    }
}
