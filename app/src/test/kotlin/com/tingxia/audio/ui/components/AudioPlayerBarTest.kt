package com.tingxia.audio.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AudioPlayerBar 的 Compose UI 测试（本地 Robolectric 单测，无需 emulator）。
 * 仅验证占位 UI 能正确渲染 + 播放/暂停按钮可切换（不接 ExoPlayer，CP4.4 才接）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioPlayerBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysTitleAndPlayButton() {
        composeTestRule.setContent {
            AudioPlayerBar(title = "测试音频", audioUrl = "https://x/a.mp3")
        }
        composeTestRule.onNodeWithText("测试音频").assertIsDisplayed()
        composeTestRule.onNodeWithText("播放").assertIsDisplayed()
    }

    @Test
    fun togglesToPauseOnClick() {
        composeTestRule.setContent {
            AudioPlayerBar(title = "测试音频", audioUrl = "https://x/a.mp3")
        }
        composeTestRule.onNodeWithText("播放").performClick()
        composeTestRule.onNodeWithText("暂停").assertIsDisplayed()
    }
}
