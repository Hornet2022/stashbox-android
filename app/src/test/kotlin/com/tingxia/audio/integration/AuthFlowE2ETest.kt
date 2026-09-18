package com.tingxia.audio.integration

import com.tingxia.audio.auth.AuthApi
import com.tingxia.audio.auth.AuthInterceptor
import com.tingxia.audio.auth.AuthResponse
import com.tingxia.audio.auth.TokenManager
import com.tingxia.audio.auth.WechatLoginRequest
import com.tingxia.audio.data.remote.ArticleApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * CP4.7-A2 E2E 测试：TokenManager + AuthInterceptor + AuthApi 全链路。
 *
 * 不起 emulator，不起 dev 服务，只在 JVM 跑。
 * Dispatchers pattern 与 ArticleListViewModelTest.kt 保持一致。
 *
 * RobolectricTestRunner 初始化 Android runtime，使 RuntimeEnvironment.getApplication() 可用。
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class AuthFlowE2ETest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }

    // Robolectric 提供 Application context (TokenManager 用 @ApplicationContext)
    private lateinit var tokenManager: TokenManager

    @Before
    fun setup() {
        server.start()
        tokenManager = TokenManager(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun makeAuthApi(authInterceptor: AuthInterceptor): AuthApi {
        val ok = OkHttpClient.Builder().addInterceptor(authInterceptor).build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(ok)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(AuthApi::class.java)
    }

    @Test
    fun login_sendsMockCode_andReturnsToken() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"mock-jwt-abc123","refresh_token":"mock-refresh-xyz","user_id":42,"expires_in":3600}""")
        )

        val authInterceptor = AuthInterceptor(tokenManager)
        val authApi = makeAuthApi(authInterceptor)
        val response: AuthResponse = authApi.wechatLogin(WechatLoginRequest("test_code_xxx"))

        assertEquals("mock-jwt-abc123", response.access_token)
        assertEquals("mock-refresh-xyz", response.refresh_token)
        assertEquals(42L, response.user_id)

        // 验证 mock server 真收到请求
        val req: RecordedRequest = server.takeRequest()
        assertEquals("/api/v1/auth/wechat-login", req.path)
        assertEquals("POST", req.method)
    }

    @Test
    fun authInterceptor_attachesJwtOnSubsequentRequests() = runTest {
        // 1. 先登录拿 token
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"jwt-attached-test","refresh_token":"r","user_id":42,"expires_in":3600}""")
        )
        val authInterceptor = AuthInterceptor(tokenManager)
        val authApi = makeAuthApi(authInterceptor)
        val loginResp = authApi.wechatLogin(WechatLoginRequest("test_code"))
        // 模拟 AuthRepository：登录成功后保存 token
        tokenManager.saveTokens(loginResp.access_token, loginResp.refresh_token, loginResp.user_id)

        // 2. 第二次请求应自动带 Authorization header
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"articles":[]}""")
        )
        // 用同一个 authInterceptor 跑 ArticleApi
        val ok = OkHttpClient.Builder().addInterceptor(authInterceptor).build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(ok)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        retrofit.create(ArticleApi::class.java).getArticles()

        // 验证 Authorization header 真附上
        val req = server.takeRequest() // 第 1 个请求（login）
        val req2 = server.takeRequest() // 第 2 个请求（articles）
        // login 不会有 Authorization（TokenManager 还没存 token，此时 token 为 null）
        // articles 应该有 Authorization: Bearer jwt-attached-test
        assertNotNull(req2.getHeader("Authorization"))
        assertTrue(req2.getHeader("Authorization")!!.startsWith("Bearer "))
    }
}
