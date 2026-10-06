package com.tingxia.audio.auth

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * AuthInterceptor 单测（本地 Robolectric 单测，无需 emulator）。
 *
 * CP3.7.0 重构后,AuthInterceptor 构造新增 RefreshClient。
 * 这里用 NoOpRefreshClient 跳过 refresh 逻辑,只验证：
 * - Authorization 头附加 / 跳过规则 / 请求体保留
 * - 401 时尝试 refresh 一次(由 FakeTokenManager 控制)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuthInterceptorTest {

    /** 假 TokenManager：覆写取 token 方法，不触碰真实 DataStore。 */
    private class FakeTokenManager(
        context: Context,
        private val accessToken: String?,
        private val refreshToken: String? = null,
    ) : TokenManager(context) {
        var cleared = false
            private set

        override suspend fun getAccessToken(): String? = accessToken
        override suspend fun getRefreshToken(): String? = refreshToken

        override suspend fun clear() {
            cleared = true
        }
    }

    /** 不真正发请求的 RefreshClient:第一次返回 null(失败),否则返回新 token */
    private class FakeRefreshClient(
        private val newToken: String? = null,
    ) : RefreshClient(
        authApiProvider = javax.inject.Provider { error("not used") },
        tokenManager = object : TokenManager(RuntimeEnvironment.getApplication()) {},
    ) {
        override suspend fun refreshIfPossible(): String? = newToken
    }

    private fun buildClient(
        token: String?,
        block: (Request) -> Unit,
        newTokenAfterRefresh: String? = null,
    ): OkHttpClient {
        val interceptor = AuthInterceptor(
            FakeTokenManager(RuntimeEnvironment.getApplication(), token),
            FakeRefreshClient(newTokenAfterRefresh),
        )
        return OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain: Interceptor.Chain ->
                block(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()
    }

    private fun request(path: String, body: RequestBody? = null): Request =
        Request.Builder().url("http://localhost$path").apply {
            if (body != null) post(body) else get()
        }.build()

    @Test
    fun `adds Authorization header when token exists`() {
        var captured: Request? = null
        val block: (Request) -> Unit = { req -> captured = req }
        val client = buildClient(token = "abc123", block = block)
        client.newCall(request("/api/v1/articles")).execute()
        assertEquals("Bearer abc123", captured!!.header("Authorization"))
    }

    @Test
    fun `skips header when token is null`() {
        var captured: Request? = null
        val block: (Request) -> Unit = { req -> captured = req }
        val client = buildClient(token = null, block = block)
        client.newCall(request("/api/v1/articles")).execute()
        assertNull(captured!!.header("Authorization"))
    }

    @Test
    fun `skips auth endpoints to avoid recursion`() {
        var captured: Request? = null
        val block: (Request) -> Unit = { req -> captured = req }
        val client = buildClient(token = "abc123", block = block)
        client.newCall(request("/api/v1/auth/wechat-login")).execute()
        assertNull(captured!!.header("Authorization"))
    }

    @Test
    fun `preserves original request body`() {
        var captured: Request? = null
        val block: (Request) -> Unit = { req -> captured = req }
        val client = buildClient(token = "abc123", block = block)
        val body = "{\"code\":\"x\"}".toRequestBody("application/json".toMediaType())
        client.newCall(request("/api/v1/articles", body)).execute()
        assertNotNull(captured!!.body)
        assertSame(body, captured!!.body)
        assertEquals("POST", captured!!.method)
    }

    // ---------------------------------------------------------------------
    // 会话失效信号（2026-10-06 修）
    //
    // 原来 refresh 失败只 Log.w + clear()，然后把原来的 401 原样返回，对外没有任何
    // 信号；而 AuthState 只在冷启动算一次。结果是 access 真过期时用户永远停在
    // 列表页"看起来还登着"，每个请求都 401，再也回不到登录页 —— 不杀进程就恢复不了。
    //
    // 下面锁两件事：确定失效时必须发信号；refresh 成功续期时绝不能发（否则每次
    // 正常续期都把在用的用户踢下线）。
    // ---------------------------------------------------------------------

    /**
     * 第一次请求返 401，refresh 之后再返 200 —— 复刻"access 过期被自动续上"这条路径。
     * 返回 terminalClient 与 [FakeTokenManager] 供断言使用。
     */
    private fun buildClientFirst401(
        tokenManager: FakeTokenManager,
        newTokenAfterRefresh: String?,
    ): OkHttpClient {
        var calls = 0
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenManager, FakeRefreshClient(newTokenAfterRefresh)))
            .addInterceptor { chain ->
                val code = if (calls++ == 0) 401 else 200
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 401) "Unauthorized" else "OK")
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()
    }

    @Test
    fun `refresh 失败时发出登录失效信号`() = runTest {
        val tokenManager = FakeTokenManager(RuntimeEnvironment.getApplication(), "old-token")
        val client = buildClientFirst401(tokenManager, newTokenAfterRefresh = null)
        val signals = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            SessionExpirySignal.events.collect { signals += it }
        }

        client.newCall(request("/api/v1/articles")).execute().close()

        assertEquals(1, signals.size)
        assertTrue("清 token 必须发生在发信号之前：UI 收到信号后要重新判断登录态", tokenManager.cleared)
    }

    @Test
    fun `refresh 成功重放时不发登录失效信号`() = runTest {
        val tokenManager = FakeTokenManager(RuntimeEnvironment.getApplication(), "old-token")
        val client = buildClientFirst401(tokenManager, newTokenAfterRefresh = "fresh-token")
        val signals = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            SessionExpirySignal.events.collect { signals += it }
        }

        val resp = client.newCall(request("/api/v1/articles")).execute()

        assertEquals(200, resp.code)
        resp.close()
        assertTrue(
            "续期成功还发信号 = 每次正常续期都把用户踢下线",
            signals.isEmpty(),
        )
        assertFalse(tokenManager.cleared)
    }

    @Test
    fun `手上没有 token 时的 401 不发失效信号`() = runTest {
        // 没有任何会话可言（本来就在登录页），不该再发一次失效把状态搅乱。
        val tokenManager = FakeTokenManager(RuntimeEnvironment.getApplication(), null)
        val client = buildClientFirst401(tokenManager, newTokenAfterRefresh = "fresh-token")
        val signals = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            SessionExpirySignal.events.collect { signals += it }
        }

        client.newCall(request("/api/v1/articles")).execute().close()

        assertTrue(signals.isEmpty())
        assertFalse(tokenManager.cleared)
    }
}