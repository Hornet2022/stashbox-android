package com.tingxia.audio.auth

import android.content.Context
import com.tingxia.audio.data.remote.ApiException
import com.tingxia.audio.data.remote.isAuthExpired
import com.tingxia.audio.ui.AUTH_EXPIRED_MESSAGE
import com.tingxia.audio.ui.auth.AuthState
import com.tingxia.audio.ui.auth.AuthViewModel
import com.tingxia.audio.ui.friendlyError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 登录失效的**分类**与**落地**：文案要真的判得出来，状态要真的翻得过去。
 *
 * ## 分类（2026-10-06 修）
 *
 * `isAuthExpired` 原来写成 `httpCode == 401 && bizCode == 40100`，但业务码来自响应体，
 * 而 `parseEnvelope` 在 body 缺失/非标准信封时给的是 `bizCode = 0`（网关/反代的裸 401
 * 连信封都没有）。于是最常见的「401 + 没有业务码」恒为 false，真实的掉登录一路掉到
 * `httpCode in 400..499` → "请求失败（401）"，一句废话，不会提示重新登录。
 * 而 ErrorMessages 的 KDoc 承诺的「401/403/407 → 登录已失效」只存在于裸 HttpException
 * 兜底分支里，parseEnvelope 只要构造出 ApiException（几乎总是）那条分支就永远到不了 ——
 * 文档描述的行为根本不存在。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuthExpiryClassificationTest {

    private fun apiEx(httpCode: Int, bizCode: Int, message: String = "") =
        ApiException(httpCode = httpCode, bizCode = bizCode, message = message)

    @Test
    fun `401 没有业务码时也要判成登录失效`() {
        // bizCode = 0 = parseEnvelope 拿不到信封时的默认值，最常见的真实情况
        val ex = apiEx(httpCode = 401, bizCode = 0)

        assertTrue(ex.isAuthExpired)
        assertEquals(AUTH_EXPIRED_MESSAGE, friendlyError(ex))
        assertNotEquals("不能再退化成无用的『请求失败（401）』", "请求失败（401）", friendlyError(ex))
    }

    @Test
    fun `401 带 40100 判成登录失效`() {
        // 后端 exceptions.py::_STATUS_CODE_MAP 把裸 401 统一映射成 code=40100
        val ex = apiEx(httpCode = 401, bizCode = 40100)

        assertTrue(ex.isAuthExpired)
        assertEquals(AUTH_EXPIRED_MESSAGE, friendlyError(ex))
    }

    @Test
    fun `401 配别的业务码不推断成全局登出`() {
        // 401 + 别的业务码说明这次 401 是针对具体请求的，整个会话没失效
        assertFalse(apiEx(httpCode = 401, bizCode = 40001).isAuthExpired)
    }

    @Test
    fun `配额用尽的 403 走付费墙而不是登录失效`() {
        // 403 + 3001：重登多少次都不会恢复配额，说成"登录已失效"会把用户引到错路上
        val msg = friendlyError(apiEx(httpCode = 403, bizCode = 3001))

        assertEquals("今日配额已用完，开通会员继续收听", msg)
    }

    @Test
    fun `归属校验的 403 不算登录失效`() {
        // 后端 Forbidden(40300) 用于 "not the owner of this article" / 管理员 tier
        val ex = apiEx(httpCode = 403, bizCode = 40300)

        assertFalse("403 表达的是无权限，不是会话失效", ex.isAuthExpired)
        assertNotEquals(AUTH_EXPIRED_MESSAGE, friendlyError(ex))
    }

    @Test
    fun `404 不会被误判成登录失效`() {
        assertFalse(apiEx(httpCode = 404, bizCode = 0).isAuthExpired)
        assertFalse(apiEx(httpCode = 404, bizCode = 40400).isAuthExpired)
        assertEquals("内容不存在或已删除", friendlyError(apiEx(httpCode = 404, bizCode = 0)))
    }

    // ------------------------------------------------------------------
    // 状态落地：0 不是合法 userId，且失效信号必须把 UI 翻回登录页
    // ------------------------------------------------------------------

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeTokenManager(context: Context) : TokenManager(context) {
        var accessToken: String? = null
        var userId: Long? = null

        override suspend fun getAccessToken(): String? = accessToken
        override suspend fun getRefreshToken(): String? = null
        override suspend fun getUserId(): Long? = userId

        override suspend fun saveTokens(access: String, refresh: String, userId: Long) {
            accessToken = access
            this.userId = userId
        }

        override suspend fun clear() {
            accessToken = null
            userId = null
        }
    }

    private class NoopAuthApi : AuthApi {
        override suspend fun wechatLogin(req: WechatLoginRequest): AuthResponse = error("not used")
        override suspend fun refresh(req: RefreshRequest): AuthResponse = error("not used")
        override suspend fun logout(): LogoutResponse = LogoutResponse()
        override suspend fun me(): UserInfoResponse = error("not used")
        override suspend fun issueToken(req: TokenIssueRequest): TokenIssueResponse = error("not used")
    }

    private fun newViewModel(tm: FakeTokenManager) =
        AuthViewModel(AuthRepository(NoopAuthApi(), tm))

    private fun newTokenManager() = FakeTokenManager(RuntimeEnvironment.getApplication())

    @Test
    fun `userId 为 0 时不算已登录`() = runTest(dispatcher) {
        val tm = newTokenManager()
        tm.saveTokens("access", "refresh", 0L) // 旧版本写脏的状态
        val vm = newViewModel(tm)

        vm.checkLogin()
        advanceUntilIdle()

        assertEquals(
            "0 是解析失败的哨兵，当成已登录会把配额/收藏记到共享的 user-0 桶",
            AuthState.NotLoggedIn,
            vm.state.value,
        )
    }

    @Test
    fun `会话失效信号把状态翻回登录页`() = runTest(dispatcher) {
        val tm = newTokenManager()
        tm.saveTokens("access", "refresh", 6892L)
        val vm = newViewModel(tm)
        vm.checkLogin()
        advanceUntilIdle()
        assertEquals(AuthState.LoggedIn(6892L), vm.state.value)

        // AuthInterceptor 清完 token 后发信号，UI 侧就是这一句
        vm.onSessionExpired()

        // AuthRoot 的 when 对 LoggedIn 之外的所有分支都渲染 LoginScreen，
        // 这里带上原因文案，用户才知道自己为什么被踢回登录页。
        val state = vm.state.value
        assertTrue("必须离开已登录态", state !is AuthState.LoggedIn)
        assertEquals(AUTH_EXPIRED_MESSAGE, (state as AuthState.Error).message)
    }

    @Test
    fun `onSessionExpired 幂等`() = runTest(dispatcher) {
        val tm = newTokenManager()
        val vm = newViewModel(tm)

        vm.onSessionExpired()
        vm.onSessionExpired() // 并发 401 会重复发信号

        assertEquals(AuthState.Error(AUTH_EXPIRED_MESSAGE), vm.state.value)
    }
}