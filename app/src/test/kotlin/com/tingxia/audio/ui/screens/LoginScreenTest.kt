package com.tingxia.audio.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LoginScreen 的 Compose UI 测试（本地 Robolectric 单测，无需 emulator）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LoginScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var loginClicked = false

    @Before
    fun setup() {
        loginClicked = false
    }

    @Test
    fun `renders title and login button`() {
        composeTestRule.setContent {
            MaterialTheme {
                LoginScreen(onLogin = { loginClicked = true })
            }
        }
        composeTestRule.onNodeWithText("听匣").assertIsDisplayed()
        composeTestRule.onNodeWithText("微信登录").assertIsDisplayed()
    }

    @Test
    fun `clicking button calls onLogin`() {
        composeTestRule.setContent {
            MaterialTheme {
                LoginScreen(onLogin = { loginClicked = true })
            }
        }
        composeTestRule.onNodeWithText("微信登录").performClick()
        assertTrue("onLogin 应被调用", loginClicked)
    }
}
