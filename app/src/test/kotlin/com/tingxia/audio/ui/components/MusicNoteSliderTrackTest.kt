package com.tingxia.audio.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 音符进度条的**轨道**护栏（R-MUSIC-TRACK 2026-10-27）。
 *
 * ## 用户反馈与它为什么必须被钉住
 *
 * 用户实拍反馈：「进度条上都有一个很明显的方形块，把进度条和圆形图形隔开了」。
 * 成因不是音符字形，也不是圆底，而是 **Material3 1.3.1 的轨道在 thumb 槽位两侧
 * 固定留 6dp 缺口**（`ThumbTrackGapSize`），且缺口末端是直角切断 ⇒ 缺口呈
 * 6dp × 16dp 的浅色矩形，夹在已播轨道与圆底之间。
 * `SliderDefaults.Track` 在 1.3.1 没有暴露 `thumbTrackGapSize` ⇒ 只能自绘轨道。
 *
 * 真机验收（小米 venus，density 560）：自绘轨道后，已播轨道像素一路接到圆底中心，
 * 圆底右侧才是未播轨道，中间没有任何浅色像素带。
 *
 * ## 两条护栏
 *
 * ① **几何契约**：已播轨道末端 = 圆底中心（不是圆底左缘）。末端一旦退回
 *    「圆心 − 半径」，缺口就会重新出现 ⇒ 红。
 * ② **与 M3 对齐**：圆底在画布里的位置必须等于 `fraction × 画布宽`
 *    （M3 1.3.1 字节码反推：`thumbOffsetX = round(trackWidth × fraction)`）。
 *    这条正是真机踩过的坑：最初按 `r + f×(W−thumbW)` 算，圆底与轨道**错开 34px**，
 *    看起来像「轨道没接上」⇒ 现在用语义节点的真实坐标钉死。
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MusicNoteSliderTrackTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `已播轨道末端必须画到圆底中心——不能停在圆底左缘`() {
        // 末端 = 圆心（不是 圆心 − 半径）：这是「轨道与圆底之间不留缝隙」的充要条件。
        assertEquals(500f, MusicNoteTrackGeometry.activeTrackEndX(0.5f, 1000f), 0.01f)
        // 反向钉：末端不得退回「圆心 − 半径」（= M3 的 6dp 缺口那一版）。
        assertTrue(
            MusicNoteTrackGeometry.activeTrackEndX(0.5f, 1000f) >
                MusicNoteTrackGeometry.thumbCenterX(0.5f, 1000f) - 10f,
        )
        // 进度为 0 时末端为 0（不画已播段）。
        assertEquals(0f, MusicNoteTrackGeometry.activeTrackEndX(0f, 1000f), 0.01f)
        // 进度为 1 时末端 = 画布宽（轨道铺满到圆底中心）。
        assertEquals(1000f, MusicNoteTrackGeometry.activeTrackEndX(1f, 1000f), 0.01f)
    }

    @Test
    fun `圆底位置与 Material3 的 thumb 槽位对齐——轨道与圆底不会错位`() {
        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                Slider(
                    // 用 0.25 而不是 0.5：两个候选公式（f×W 与 r+f×(W−thumbW)）在 f=0.5
                    // 处恰好相等，测不出错位；f=0.25 时两者相差 5px。
                    value = 0.25f,
                    onValueChange = {},
                    thumb = { MusicNoteSliderThumb(color = Color.Magenta) },
                    track = {
                        MusicNoteSliderTrack(
                            fraction = 0.25f,
                            activeColor = Color.Red,
                            inactiveColor = Color.Blue,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val track = composeTestRule.onNodeWithTag(MusicNoteSliderTrackTestTag, useUnmergedTree = true)
            .fetchSemanticsNode()
        val thumb = composeTestRule.onNodeWithTag(MusicNoteSliderThumbTestTag, useUnmergedTree = true)
            .fetchSemanticsNode()

        val trackWidth = track.boundsInRoot.right - track.boundsInRoot.left
        val thumbCenter = (thumb.boundsInRoot.left + thumb.boundsInRoot.right) / 2f

        // 圆心（画布本地）必须 = fraction × 画布宽，且与 M3 的 thumb 槽位落在同一处。
        val expectedLocalCenter = MusicNoteTrackGeometry.thumbCenterX(0.25f, trackWidth)
        val actualLocalCenter = thumbCenter - track.boundsInRoot.left
        assertTrue(
            "圆底中心 $actualLocalCenter 应等于 fraction×画布宽 $expectedLocalCenter（画布宽 $trackWidth）",
            kotlin.math.abs(actualLocalCenter - expectedLocalCenter) <= 1f,
        )
    }
}
