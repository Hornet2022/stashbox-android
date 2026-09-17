package com.tingxia.audio.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.audio.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * AudioPlayerBar 的 Compose UI 测试（本地 Robolectric 单测，无需 emulator）。
 * CP4.4 起：传入真实 [PlayerController]，验证 UI 接真状态 + 点击触发 play/pause。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioPlayerBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var controller: PlayerController

    @Before
    fun setup() {
        controller = PlayerController(RuntimeEnvironment.getApplication())
    }

    @Test
    fun displaysTitleAndPlayButton() {
        composeTestRule.setContent {
            AudioPlayerBar(
                title = "测试音频",
                audioUrl = "https://x/a.mp3",
                playerController = controller,
            )
        }
        composeTestRule.onNodeWithText("测试音频").assertIsDisplayed()
        composeTestRule.onNodeWithText("播放").assertIsDisplayed()
    }

    @Test
    fun clickPlay_triggersControllerPlay_andShowsPause() {
        composeTestRule.setContent {
            AudioPlayerBar(
                title = "测试音频",
                audioUrl = "https://x/a.mp3",
                playerController = controller,
            )
        }
        composeTestRule.onNodeWithText("播放").performClick()
        composeTestRule.onNodeWithText("暂停").assertIsDisplayed()
        // 验证真实状态机进入 PLAYING
        assertEquals(PlaybackState.PLAYING, controller.state.value)
    }
}
