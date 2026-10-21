package com.tingxia.audio.ui.account

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.tingxia.audio.auth.TokenManager
import com.tingxia.audio.data.local.UserDataCleaner
import com.tingxia.audio.data.remote.AccountApi
import com.tingxia.audio.data.remote.AccountDeleteRequest
import com.tingxia.audio.data.remote.AccountDeleteResponse
import com.tingxia.audio.data.repository.AccountRepository
import com.tingxia.audio.data.repository.ConsentLocalStore
import kotlinx.coroutines.yield
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 注销账号界面的 Compose UI 测试（2026-10-21）。
 *
 * 锁的是**按钮的可点状态**与确认短语的绑定关系：
 * 服务端的硬性要求是 `confirm_text` 逐字等于 `注销听匣`，
 * 客户端如果让用户点一个必失败的按钮，那就是把"确认"这层保护做成了摆设。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AccountDeletionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class FakeAccountApi : AccountApi {
        override suspend fun deleteAccount(body: AccountDeleteRequest) =
            AccountDeleteResponse(deleted = true, articles_soft_deleted = 1)
    }

    private class FakeTokenManager(context: Context) : TokenManager(context) {
        override suspend fun getAccessToken(): String? = null
        override suspend fun getRefreshToken(): String? = null
        override suspend fun getUserId(): Long? = 42L
        override suspend fun saveTokens(access: String, refresh: String, userId: Long) = Unit
        override suspend fun clear() {
            yield()
        }
    }

    private class FakeUserDataCleaner : UserDataCleaner {
        override suspend fun clearAll() {
            yield()
        }
    }

    private fun viewModel(): AccountDeletionViewModel {
        val context = RuntimeEnvironment.getApplication()
        val repository = AccountRepository(
            FakeAccountApi(),
            FakeTokenManager(context),
            FakeUserDataCleaner(),
            ConsentLocalStore(context),
        )
        return AccountDeletionViewModel(repository)
    }

    @Test
    fun `确认短语不匹配时注销按钮不可点`() {
        val vm = viewModel()
        composeTestRule.setContent {
            MaterialTheme {
                AccountDeletionScreen(onBack = {}, viewModel = vm)
            }
        }

        // 初始：什么都没输入 → 按钮必须锁着
        composeTestRule.onNodeWithText("确认注销账号").assertIsNotEnabled()

        // 逐字输入正确短语 → 按钮才解禁
        composeTestRule.onNodeWithTag(CONFIRM_FIELD_TAG)
            .performScrollTo()
            .performTextInput("注销听匣")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("确认注销账号").assertIsEnabled()
    }

    @Test
    fun `短语多一个字时按钮仍然不可点`() {
        val vm = viewModel()
        composeTestRule.setContent {
            MaterialTheme {
                AccountDeletionScreen(onBack = {}, viewModel = vm)
            }
        }

        composeTestRule.onNodeWithTag(CONFIRM_FIELD_TAG)
            .performScrollTo()
            .performTextInput("注销听匣x")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("确认注销账号").assertIsNotEnabled()
    }

    @Test
    fun `界面明示了不可逆与确认短语`() {
        val vm = viewModel()
        composeTestRule.setContent {
            MaterialTheme {
                AccountDeletionScreen(onBack = {}, viewModel = vm)
            }
        }

        // 用户必须能看见"这不可逆"和"要打哪几个字"
        composeTestRule.onNodeWithText("注销后无法恢复").assertExists()
        composeTestRule.onNodeWithText("输入「注销听匣」以确认").assertExists()
    }
}
