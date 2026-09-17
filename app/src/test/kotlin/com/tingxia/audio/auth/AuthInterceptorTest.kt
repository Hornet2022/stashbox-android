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
import org.junit.Assert.assertFalse
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
 * 用一个「记录最终请求」的终止拦截器捕获经过 AuthInterceptor 后的 Request，
 * 据此断言 Authorization 头 / auth 端点跳过 / 请求体保留。
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
    }

    private fun buildClient(token: String?, block: (Request) -> Unit): OkHttpClient {
        val interceptor = AuthInterceptor(
            FakeTokenManager(RuntimeEnvironment.getApplication(), token),
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
        val client = buildClient("abc123") { captured = it }
        client.newCall(request("/api/v1/articles")).execute()
        assertEquals("Bearer abc123", captured!!.header("Authorization"))
    }

    @Test
    fun `skips header when token is null`() {
        var captured: Request? = null
        val client = buildClient(null) { captured = it }
        client.newCall(request("/api/v1/articles")).execute()
        assertNull(captured!!.header("Authorization"))
    }

    @Test
    fun `skips auth endpoints to avoid recursion`() {
        var captured: Request? = null
        val client = buildClient("abc123") { captured = it }
        client.newCall(request("/api/v1/auth/wechat-login")).execute()
        assertNull(captured!!.header("Authorization"))
    }

    @Test
    fun `preserves original request body`() {
        var captured: Request? = null
        val client = buildClient("abc123") { captured = it }
        val body = "{\"code\":\"x\"}".toRequestBody("application/json".toMediaType())
        client.newCall(request("/api/v1/articles", body)).execute()
        // 请求体实例未被拦截器替换
        assertNotNull(captured!!.body)
        assertSame(body, captured!!.body)
        // 原始方法未被篡改
        assertEquals("POST", captured!!.method)
    }
}
