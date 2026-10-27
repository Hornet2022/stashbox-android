package com.tingxia.audio.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.tingxia.audio.audio.PlaybackState
import com.tingxia.audio.audio.PlayerController
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 全局迷你播放条 [GlobalPlayerBar] 的**位置**回归。
 *
 * ## 为什么这条测试必须存在
 *
 * 0.4.8 首发时，播放条出现在了**屏幕顶部**（用户当场发现）。
 * 根因是布局对齐的层级，不是"写错了一个值"：
 *
 * - [GlobalPlayerBar] 是 `MainActivity` 里 `Box(Modifier.fillMaxSize())` 的子项，
 *   那个父 Box 的 `contentAlignment` 是**默认的 TopStart**；
 * - 播放条自己的 Box 只写了 `fillMaxWidth()`（高度 = 内容高度），
 *   于是父 Box 把它摆到了**顶部**；
 * - 它内部的 `contentAlignment = BottomCenter` 只在那个「只有内容高度」的
 *   小 Box 内生效 —— 上下沿重合，等于没写。
 *
 * ⇒ **BottomCenter 只有父容器撑满时才有效。**
 *
 * 这类 bug 的恶劣之处：代码读起来完全正常（"底部对齐"写得明明白白），
 * 编译、单测、lint 全绿，只有真机看得见。所以这里**不看代码**，
 * 直接取 Compose 布局后的**节点坐标**来断言 —— 位置错就是错。
 *
 * ## 断言为什么用「播放/暂停按钮」当锚点
 *
 * 不用标题文字节点：文字在栏里居中，文字的底边 ≠ 栏的底边（栏底还有 12dp 内边距），
 * 拿它比会得到一个"看起来像差了一点、其实是对的"的结果。
 * 播放/暂停按钮是 48dp 的方块，位置确定、语义唯一（contentDescription 随状态切换）。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GlobalPlayerBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var controller: PlayerController

    private val audioUrl = "https://example.com/audio.mp3"

    @Before
    fun setup() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        controller = PlayerController(player, RuntimeEnvironment.getApplication())
    }

    /**
     * 按 `MainActivity` 的真实结构搭场景：外层 `Box(Modifier.fillMaxSize())`
     * 里叠播放条。**必须一模一样** —— 这正是 0.4.8 出事的那层结构。
     */
    private fun showGlobalBar(route: String? = "home") {
        composeTestRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    GlobalPlayerBar(
                        playerController = controller,
                        currentRoute = route,
                        onClick = {},
                    )
                }
            }
        }
    }

    /** 播放/暂停按钮的语义标签随状态在「播放」/「暂停」之间切。 */
    private fun playPauseLabel(): String =
        if (controller.state.value == PlaybackState.PLAYING) "暂停" else "播放"

    @Test
    fun `播放条在屏幕底部而不是顶部`() {
        controller.play(audioUrl = audioUrl, title = "底部播放条", articleId = "art_1")
        showGlobalBar()

        val root = composeTestRule.onRoot().fetchSemanticsNode()
        val bar = composeTestRule.onNodeWithContentDescription(playPauseLabel())
            .fetchSemanticsNode()

        val rootBounds = root.boundsInRoot
        val barBounds = bar.boundsInRoot

        assertTrue(
            "播放条必须落在屏幕下半部分（按钮 center.y=${barBounds.center.y}，" +
                "屏幕高=${rootBounds.height}）—— 0.4.8 的事故就是它跑到了顶部",
            barBounds.center.y > rootBounds.height / 2,
        )
    }

    @Test
    fun `播放条贴着底边而不是浮在屏幕中间`() {
        // 反向确认上一条：不是"靠下"就算过 —— 它得真的**贴着底边**。
        // 中间浮着一截同样是错的（说明对齐落在了别的高度上）。
        controller.play(audioUrl = audioUrl, title = "贴底", articleId = "art_1")
        showGlobalBar()

        val root = composeTestRule.onRoot().fetchSemanticsNode()
        val bar = composeTestRule.onNodeWithContentDescription(playPauseLabel())
            .fetchSemanticsNode()

        val gapToBottom = root.boundsInRoot.bottom - bar.boundsInRoot.bottom
        assertTrue(
            "播放条与屏幕底边之间不应有整截空白（实际 gap=${gapToBottom}px，" +
                "屏幕高=${root.boundsInRoot.height}px）",
            gapToBottom < root.boundsInRoot.height / 5f,
        )
    }

    @Test
    fun `没有音频时不显示播放条`() {
        // App 刚启动、还没播过任何东西 —— 底部不该凭空出现一条空条子
        showGlobalBar()

        composeTestRule.onNodeWithContentDescription("播放").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("暂停").assertDoesNotExist()
    }

    @Test
    fun `全屏播放页不叠迷你条`() {
        // 全屏播放器本身就是展开的控件，再叠一条是重复的
        controller.play(audioUrl = audioUrl, title = "全屏", articleId = "art_1")
        showGlobalBar(route = "player")

        composeTestRule.onNodeWithContentDescription("播放").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("暂停").assertDoesNotExist()
    }
}
