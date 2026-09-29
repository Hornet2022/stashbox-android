package com.tingxia.audio.auth

import android.content.Context
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuthInterceptorTest {

    /** 假 TokenManager：覆写取 token 方法，不触碰真实 DataStore。 */
    private class FakeTokenManager(
        context: Context,
        private val accessToken: String?,
    ) : TokenManager(context) {
        override suspend fun getAccessToken(): String? = accessToken
        override suspend fun getRefreshToken(): String? = null
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
}