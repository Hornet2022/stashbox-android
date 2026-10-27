package com.tingxia.audio.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 宽屏限宽（[ResponsiveContent]）的回归护栏。
 *
 * ## 这条测试在防什么
 *
 * 2026-10-27 之前，全仓**没有任何窗口尺寸判断** ——
 * 列表与正文一律 `fillMaxWidth` + 16dp 边距。平板/折叠屏展开态下，
 * 一行正文能拉到 1000+dp 字符宽，眼睛找行首会累（Material 3 R3.4）。
 *
 * 加了限宽之后，最容易出的错有两个方向：
 *
 * 1. **限了宽但没居中** —— 内容贴左边，右边空一大片。
 *    根因是 `widthIn(max = ...)` 只改**尺寸**不改**位置**，
 *    而 `Column`/`Box` 的默认对齐是 `Start`。
 * 2. **手机端被误伤** —— Compact 下不该有任何变化，
 *    否则等于给几亿台手机改了一次版。
 *
 * ⇒ 这两条都要断言，而且必须拿**渲染后的坐标**来断言，
 * 不能只读代码说"我写了 widthIn"（这正是 0.4.8 播放条事故的教训：
 * 声明在错误的层级里会静默失效，编译绿、单测绿、lint 绿，只有真机看得见）。
 *
 * ## 怎么造宽屏
 *
 * Robolectric 的 `@Config(qualifiers = "w1000dp")` 能把窗口宽度设成 1000dp，
 * 从而让 `calculateWindowSizeClass` 判定为 Expanded。
 */
@RunWith(RobolectricTestRunner::class)
class ResponsiveContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun showResponsive() {
        composeTestRule.setContent {
            MaterialTheme {
                ResponsiveContent(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(TAG),
                    ) {
                        Text("内容", modifier = Modifier.size(10.dp))
                    }
                }
            }
        }
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1000dp-h1200dp")
    fun `宽屏下内容限宽并居中`() {
        showResponsive()

        val root = composeTestRule.onRoot().fetchSemanticsNode()
        val content = composeTestRule.onNodeWithTag(TAG).fetchSemanticsNode()

        val rootWidth = root.boundsInRoot.width
        val contentWidth = content.boundsInRoot.width

        // 限宽：不能比 840dp 宽（转成 px 再比，见下面的换算）
        val maxWidthPx = with(composeTestRule.density) { Responsive.MaxContentWidth.toPx() }
        assertTrue(
            "宽屏下内容宽度=${contentWidth}px 不应超过上限=${maxWidthPx}px",
            contentWidth <= maxWidthPx + 1f,
        )

        // 居中：左右留白应当相等
        val leftGap = content.boundsInRoot.left - root.boundsInRoot.left
        val rightGap = root.boundsInRoot.right - content.boundsInRoot.right
        assertEquals(
            "宽屏下内容必须居中：左边距=${leftGap}px，右边距=${rightGap}px",
            leftGap.toInt(),
            rightGap.toInt(),
        )
    }

    @Test
    @Config(sdk = [33], qualifiers = "w411dp-h891dp")
    fun `手机端不改变原有布局`() {
        // Compact 下 widthIn(max=840) 本来就不生效 —— 这条测试钉住
        // "加限宽不能改变手机端的观感"。
        showResponsive()

        val root = composeTestRule.onRoot().fetchSemanticsNode()
        val content = composeTestRule.onNodeWithTag(TAG).fetchSemanticsNode()

        assertEquals(
            "Compact 下内容应占满整宽（宽度=${content.boundsInRoot.width}px，" +
                "屏幕宽=${root.boundsInRoot.width}px）",
            root.boundsInRoot.width.toInt(),
            content.boundsInRoot.width.toInt(),
        )
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1000dp-h1200dp")
    fun `限宽常量的取值符合 Material 3 建议`() {
        // 840dp 是 R3.4 给的建议值。改了它要有人知道 —— 写成断言。
        assertEquals(
            "宽屏最大内容宽度应保持 840dp（Material 3 R3.4 建议值）",
            840,
            Responsive.MaxContentWidth.value.toInt(),
        )
    }

    private companion object {
        const val TAG = "responsive-content"
    }
}
