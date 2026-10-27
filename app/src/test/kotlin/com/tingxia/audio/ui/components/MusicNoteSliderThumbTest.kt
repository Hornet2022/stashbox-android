package com.tingxia.audio.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.tingxia.audio.audio.PlayerController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 音符进度指示器（R-MUSIC-THUMB 2026-10-27）的渲染护栏。
 *
 * 背景：两处播放进度条（底部常驻条 AudioPlayerBar + 全屏播放器 ProgressSection）
 * 的进度指示器被替换为「音符图标」。这类"声明出来的意图"（用了 thumb 参数）
 * 只读代码验收不可靠 —— 编译绿不代表音符真的渲染出来了。所以这里用 testTag
 * 钉住「音符 thumb 确实出现在渲染树里」。
 *
 * ## 为什么要钉「没有实心底」
 *
 * 用户看了一轮带圆盘的版本后要求「音符外围不用框 / 搞成透明的」。
 * 这个要求只写在代码注释里是不够的 —— 将来有人"觉得空心圆更好看"加回一个
 * `background(CircleShape)`，代码照样编译绿、测试也照样绿，但用户明确说过不要。
 * 所以这里直接钉尺寸：透明版没有任何背景绘制，节点的尺寸**只能**来自图标本身
 * （22dp 方形槽位）。若有人加回 24dp 的圆盘衬底，尺寸断言当场转红。
 *
 * 有牙齿的证据：
 * - 去掉 Slider 的 thumb 参数（回到默认 thumb）→ onNodeWithTag 找不到 tag，红；
 * - 给 MusicNoteSliderThumb 加回 background(size(24.dp)) → 尺寸断言红。
 */
@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class) // Slider.thumb 是 experimental API
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MusicNoteSliderThumbTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `音符指示器渲染在语义树里`() {
        composeTestRule.setContent {
            MusicNoteSliderThumb(color = Color.Magenta)
        }
        composeTestRule.onNodeWithTag(MusicNoteSliderThumbTestTag, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `圆底尺寸是 20dp——护栏钉住视觉规格`() {
        composeTestRule.setContent {
            MusicNoteSliderThumb(color = Color.Magenta)
        }
        // 用户确认「之前的圆形挺好看的」⇒ 圆底要留着，但尺寸钉死 20dp：
        // 加回实心圆底（background(CircleShape)）时这里会红，防止尺寸被悄悄改大
        // （原版 M3 竖条 44dp 高正是用户抱怨「太大太突兀」的根因）。
        composeTestRule.onNodeWithTag(MusicNoteSliderThumbTestTag, useUnmergedTree = true)
            .assertWidthIsEqualTo(20.dp)
            .assertHeightIsEqualTo(20.dp)
    }

    @Test
    fun `底部播放条的 Slider 使用音符 thumb`() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        val controller = PlayerController(player, RuntimeEnvironment.getApplication())
        composeTestRule.setContent {
            MaterialTheme {
                AudioPlayerBar(
                    title = "标题",
                    audioUrl = "https://example.com/audio.mp3",
                    articleId = "art_1",
                    playerController = controller,
                )
            }
        }
        composeTestRule.onNodeWithTag(MusicNoteSliderThumbTestTag, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `带 thumb 参数的 Slider 渲染音符指示器`() {
        composeTestRule.setContent {
            Slider(
                value = 0.5f,
                onValueChange = {},
                thumb = { MusicNoteSliderThumb(color = Color.Blue) },
            )
        }
        composeTestRule.onNodeWithTag(MusicNoteSliderThumbTestTag, useUnmergedTree = true)
            .assertExists()
    }
}
