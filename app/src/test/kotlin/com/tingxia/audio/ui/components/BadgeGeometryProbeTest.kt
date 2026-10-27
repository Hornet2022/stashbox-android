package com.tingxia.audio.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class BadgeGeometryProbeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun probe() {
        composeTestRule.setContent {
            MaterialTheme {
                NotificationBadgeIcon(count = 120, onClick = {})
            }
        }
        val bell = composeTestRule.onNodeWithContentDescription("通知").fetchSemanticsNode().boundsInRoot
        val badge = composeTestRule.onNodeWithContentDescription("120 条未读通知").fetchSemanticsNode().boundsInRoot
        println("PROBE density=${composeTestRule.density.density}")
        println("PROBE bell=$bell (w=${bell.width} h=${bell.height})")
        println("PROBE badge=$badge (w=${badge.width} h=${badge.height})")
        println("PROBE badgeDp=${badge.width / composeTestRule.density.density}")
    }
}
