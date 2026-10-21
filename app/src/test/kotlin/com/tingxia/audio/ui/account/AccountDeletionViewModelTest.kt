package com.tingxia.audio.ui.account

import android.content.Context
import com.tingxia.audio.auth.TokenManager
import com.tingxia.audio.data.local.UserDataCleaner
import com.tingxia.audio.data.remote.AccountApi
import com.tingxia.audio.data.remote.AccountDeleteRequest
import com.tingxia.audio.data.remote.AccountDeleteResponse
import com.tingxia.audio.data.repository.AccountRepository
import com.tingxia.audio.data.repository.ConsentLocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 注销账号的 ViewModel 单测（2026-10-21）。
 *
 * ## 这里锁什么
 *
 * - **确认短语必须逐字匹配**才能按按钮（首尾空白容忍，错短语一律不放行）；
 * - **短语不对就不发请求**（不能把一个注定 400 的请求发出去）；
 * - **注销成功必须清本机**（token + Room/缓存/后台任务 + 本地同意记录）——
 *   共享手机上下一个人能看到上一个用户的下载与续播位置，就是这里漏了；
 * - **重复注销（already_deleted）照样要登出**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AccountDeletionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        buildFakes()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── 假实现 ──

    /** 假 AccountApi：记录收到的请求。 */
    private class FakeAccountApi(
        val response: AccountDeleteResponse = AccountDeleteResponse(
            deleted = true,
            already_deleted = false,
            anonymized_fields = listOf("open_id", "nickname"),
            articles_soft_deleted = 12,
        ),
    ) : AccountApi {
        val requests = mutableListOf<AccountDeleteRequest>()

        override suspend fun deleteAccount(body: AccountDeleteRequest): AccountDeleteResponse {
            requests += body
            return response
        }
    }

    /** 假 TokenManager：记录"清过没有"。 */
    private class FakeTokenManager(context: Context) : TokenManager(context) {
        var cleared = false
            private set

        override suspend fun getAccessToken(): String? = null
        override suspend fun getRefreshToken(): String? = null
        override suspend fun getUserId(): Long? = 42L
        override suspend fun saveTokens(access: String, refresh: String, userId: Long) = Unit

        override suspend fun clear() {
            // 真实实现是 DataStore 写盘（挂起点），这里也留一个挂起点。
            yield()
            cleared = true
        }
    }

    /** 假 UserDataCleaner：记录"本机数据清过没有"。 */
    private class FakeUserDataCleaner : UserDataCleaner {
        var cleared = false
            private set

        override suspend fun clearAll() {
            yield()
            cleared = true
        }
    }

    private lateinit var context: Context
    private lateinit var api: FakeAccountApi
    private lateinit var tokenManager: FakeTokenManager
    private lateinit var cleaner: FakeUserDataCleaner
    private lateinit var localStore: ConsentLocalStore

    private fun vm(): AccountDeletionViewModel {
        val repository = AccountRepository(api, tokenManager, cleaner, localStore)
        return AccountDeletionViewModel(repository)
    }

    private fun buildFakes() {
        context = RuntimeEnvironment.getApplication()
        api = FakeAccountApi()
        tokenManager = FakeTokenManager(context)
        cleaner = FakeUserDataCleaner()
        localStore = ConsentLocalStore(context)
    }

    // ── 用例 ──

    @Test
    fun `确认短语不匹配时按钮保持禁用`() {
        val vm = vm()

        // 空
        vm.onConfirmTextChange("")
        assertFalse("空短语不能放行", vm.uiState.value.canSubmit)

        // 差一个字
        vm.onConfirmTextChange("注销听匣x")
        assertFalse("多一个字不能放行", vm.uiState.value.canSubmit)

        // 少一个字
        vm.onConfirmTextChange("注销")
        assertFalse("少一个字不能放行", vm.uiState.value.canSubmit)

        // 完全不是这句话
        vm.onConfirmTextChange("退出登录")
        assertFalse("错短语不能放行", vm.uiState.value.canSubmit)

        // 首尾空白：输入法在中文候选词后面常带一个空格，容忍它
        vm.onConfirmTextChange("  注销听匣  ")
        assertTrue("trim 之后应放行", vm.uiState.value.canSubmit)

        // 逐字一致
        vm.onConfirmTextChange("注销听匣")
        assertTrue("完全一致应放行", vm.uiState.value.canSubmit)
    }

    @Test
    fun `短语不对时不发请求`() = testScope.runTest {
        val vm = vm()

        vm.onConfirmTextChange("注销听匣x")
        vm.submit()
        advanceUntilIdle()

        assertTrue("注定失败的请求不该发出去", api.requests.isEmpty())
        assertNotNull("界面上要说明为什么没反应", vm.uiState.value.error)
        assertFalse("没成功就不能是提交中", vm.uiState.value.isSubmitting)
    }

    @Test
    fun `注销成功会清掉本机 token 与数据`() = testScope.runTest {
        val vm = vm()
        vm.onConfirmTextChange("注销听匣")

        vm.submit()
        advanceUntilIdle()

        assertEquals("应该只发一次注销请求", 1, api.requests.size)
        assertEquals("请求里的确认短语必须逐字一致", "注销听匣", api.requests.single().confirm_text)
        assertTrue("注销后必须清 token", tokenManager.cleared)
        assertTrue(
            "注销后必须清本机数据（下载台账/播放进度/音频缓存/后台任务）",
            cleaner.cleared,
        )
        assertNotNull("结果要摆到界面上", vm.uiState.value.result)
        assertEquals(12, vm.uiState.value.result?.articlesSoftDeleted)
        assertFalse(
            "成功后按钮必须锁死，不能再点第二次",
            vm.uiState.value.canSubmit,
        )
    }

    @Test
    fun `账号此前已注销时照样登出`() = testScope.runTest {
        api = FakeAccountApi(
            response = AccountDeleteResponse(
                deleted = true,
                already_deleted = true,
                anonymized_fields = emptyList(),
                articles_soft_deleted = 0,
            ),
        )
        val vm = vm()
        vm.onConfirmTextChange("注销听匣")

        vm.submit()
        advanceUntilIdle()

        assertTrue("幂等返回也要清 token", tokenManager.cleared)
        assertTrue("幂等返回也要清本机数据", cleaner.cleared)
        assertTrue(
            "界面上要能区分「已经注销过」",
            vm.uiState.value.result?.alreadyDeleted == true,
        )
    }

    @Test
    fun `本地的同意记录也要一起清掉`() = testScope.runTest {
        localStore.setAcceptedVersion(42L, "v1")
        val vm = vm()
        vm.onConfirmTextChange("注销听匣")

        vm.submit()
        advanceUntilIdle()

        assertEquals(
            "注销后本机不该还留着「这个人同意过什么」",
            "",
            localStore.getAcceptedVersion(42L),
        )
    }
}
