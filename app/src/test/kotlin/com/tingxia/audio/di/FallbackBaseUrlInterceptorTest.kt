package com.tingxia.audio.di

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * [FallbackBaseUrlInterceptor] 的行为边界。
 *
 * ## 为什么这个测试重要
 * 兜底逻辑的**分界**决定了它安不安全：
 *   · 连接层失败 → 换地址重试是干净的 → 该切
 *   · 读超时 / 业务错误 → 请求可能已经发出去了，或换地址也没用 → 切了有害
 *
 * 分界写错的后果分两种：切少了 = 白兜底；切多了 = **同一个操作提交两次**
 * （"删除文章"这种不幂等的操作会真删两次）。
 */
class FallbackBaseUrlInterceptorTest {

    private lateinit var fallbackServer: MockWebServer
    private lateinit var primaryServer: MockWebServer

    @Before
    fun setUp() {
        // 熔断状态是**进程级共享**的（见 PrimaryEndpoint）→ 每个用例开始前
        // 必须重置，否则上一个用例标记的熔断会污染下一个。
        PrimaryEndpoint.markPrimaryHealthy()
        fallbackServer = MockWebServer()
        fallbackServer.start()
        primaryServer = MockWebServer()
        primaryServer.start()
    }

    @After
    fun tearDown() {
        fallbackServer.shutdown()
        primaryServer.shutdown()
    }

    /**
     * 假 interceptor 的「放行」分支：把请求真的发出去。
     *
     * 写成 lambda 里的 chainProceed(it) 是因为 Interceptor 的 lambda 签名
     * 拿不到 chain —— 这里用一个小 helper 把它传进来。
     */
    private fun chainProceed(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request())

    private fun client(fallback: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(FallbackBaseUrlInterceptor(fallback))
            .build()

    @Test
    fun `主地址正常时不切换`() {
        primaryServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val client = client(fallbackServer.url("/").toString())

        val body = client.newCall(Request.Builder().url(primaryServer.url("/x")).build())
            .execute().use { it.body!!.string() }

        assertEquals("ok", body)
        // 备用服务器一个请求都不该收到
        assertEquals(0, fallbackServer.requestCount)
    }

    @Test
    fun `连接被拒时切到备用地址`() {
        // 127.0.0.1:1 上没有服务 → ConnectException（连接层失败）
        val client = client(fallbackServer.url("/").toString())
        fallbackServer.enqueue(MockResponse().setResponseCode(200).setBody("fallback-ok"))

        val body = client.newCall(Request.Builder().url("http://127.0.0.1:1/x").build())
            .execute().use { it.body!!.string() }

        assertEquals("fallback-ok", body)
        assertEquals(1, fallbackServer.requestCount)
        // 路径必须原样带过去（只换 host，不改 path/query）
        assertEquals("/x", fallbackServer.takeRequest().path)
    }

    @Test
    fun `get 遇到连接被重置时切换`() {
        // 真机上遇到过的形态：用户所在网络把到域名的 TLS 连接直接 RST 掉
        // → java.net.SocketException: Connection reset。
        // 第一版按异常类型判定，漏了这类 → 用户看到的还是「连不上」。
        // GET 是幂等的 → 换地址重试没有副作用 → 该切。
        val reset = SocketException("Connection reset")
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val client = OkHttpClient.Builder()
            // ⚠️ 顺序有意义：fallback 必须注册在**前面**（更外层）才能捕获到内层的异常
            .addInterceptor(FallbackBaseUrlInterceptor(fallbackServer.url("/").toString()))
            // 只在第一次抛：重试时应该真的把请求发出去（此时 URL 已是备用地址）
            .addInterceptor(Interceptor { chain ->
                if (calls.getAndIncrement() == 0) throw reset
                chainProceed(chain)
            })
            .build()
        fallbackServer.enqueue(MockResponse().setResponseCode(200).setBody("fallback-ok"))

        val body = client.newCall(Request.Builder().url(primaryServer.url("/x")).build())
            .execute().use { it.body!!.string() }

        assertEquals("fallback-ok", body)
        assertEquals(1, fallbackServer.requestCount)
    }

    @Test
    fun `post 遇到连接被重置时不切换`() {
        // 同样的 SocketException，但方法是有副作用的 POST：
        // 连接被重置时请求**可能已经到达服务端** → 重试可能提交两遍
        // （"删除文章"会真删两次）→ 不能切。
        val reset = SocketException("Connection reset")
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val client = OkHttpClient.Builder()
            // ⚠️ 顺序有意义：fallback 必须注册在**前面**（更外层）才能捕获到内层的异常
            .addInterceptor(FallbackBaseUrlInterceptor(fallbackServer.url("/").toString()))
            // 只在第一次抛：重试时应该真的把请求发出去（此时 URL 已是备用地址）
            .addInterceptor(Interceptor { chain ->
                if (calls.getAndIncrement() == 0) throw reset
                chainProceed(chain)
            })
            .build()

        val thrown = runCatching {
            client.newCall(
                Request.Builder().url(primaryServer.url("/x")).post("{}".toRequestBody(null)).build()
            ).execute()
        }.exceptionOrNull()

        assertTrue("期望原样抛出，实际：$thrown", thrown is SocketException)
        assertEquals(0, fallbackServer.requestCount)
    }

    @Test
    fun `post 遇到 dns 解析失败时切换`() {
        // 连接阶段失败 → 请求肯定没发出去 → 有副作用的 POST 也能安全重试
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(FallbackBaseUrlInterceptor(fallbackServer.url("/").toString()))
            .addInterceptor(Interceptor { chain ->
                if (calls.getAndIncrement() == 0) throw UnknownHostException("api.stashbox.cn")
                chainProceed(chain)
            })
            .build()
        fallbackServer.enqueue(MockResponse().setResponseCode(200).setBody("fallback-ok"))

        val body = client.newCall(
            Request.Builder().url(primaryServer.url("/x")).post("{}".toRequestBody(null)).build()
        ).execute().use { it.body!!.string() }

        assertEquals("fallback-ok", body)
        assertEquals(1, fallbackServer.requestCount)
    }

    @Test
    fun `登录这种幂等 post 遇到连接被重置时切换`() {
        // 真机上最要命的一个：登录是 POST，网络把到域名的连接 RST 掉
        // → 不切 → 「登录失败：网络不给力」→ App 完全不可用。
        // 发 token 是幂等的（多签一个 token 而已）→ 该切。
        val reset = SocketException("Connection reset")
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(FallbackBaseUrlInterceptor(fallbackServer.url("/").toString()))
            .addInterceptor(Interceptor { chain ->
                if (calls.getAndIncrement() == 0) throw reset
                chainProceed(chain)
            })
            .build()
        fallbackServer.enqueue(MockResponse().setResponseCode(200).setBody("{\"token\":\"t\"}"))

        val body = client.newCall(
            Request.Builder().url(primaryServer.url("/api/v1/auth/wechat-login"))
                .post("\"{\"code\":\"x\"}\"".toRequestBody(null)).build()
        ).execute().use { it.body!!.string() }

        assertTrue(body.contains("token"))
        assertEquals(1, fallbackServer.requestCount)
    }

    @Test
    fun `进度上报这种幂等 post 遇到连接被重置时切换`() {
        val reset = SocketException("Connection reset")
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(FallbackBaseUrlInterceptor(fallbackServer.url("/").toString()))
            .addInterceptor(Interceptor { chain ->
                if (calls.getAndIncrement() == 0) throw reset
                chainProceed(chain)
            })
            .build()
        fallbackServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val code = client.newCall(
            Request.Builder().url(primaryServer.url("/api/v1/articles/art-1/progress"))
                .post("{}".toRequestBody(null)).build()
        ).execute().use { it.code }

        assertEquals(200, code)
        assertEquals(1, fallbackServer.requestCount)
    }

    @Test
    fun `剪藏这种幂等 post 遇到连接被重置时切换`() {
        // 剪藏（POST /api/v1/articles）后端是 CP-DUPLICATE-CLIP：
        // 先查重，重复提交同一链接**复用已有文章、配额不动** → 幂等 → 该切。
        // 不切的后果：用户在坏网络下点「剪藏」直接失败 —— 那是 App 的核心功能。
        val reset = SocketException("Connection reset")
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(FallbackBaseUrlInterceptor(fallbackServer.url("/").toString()))
            .addInterceptor(Interceptor { chain ->
                if (calls.getAndIncrement() == 0) throw reset
                chainProceed(chain)
            })
            .build()
        fallbackServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val code = client.newCall(
            Request.Builder().url(primaryServer.url("/api/v1/articles"))
                .post("{}".toRequestBody(null)).build()
        ).execute().use { it.code }

        assertEquals(200, code)
        assertEquals(1, fallbackServer.requestCount)
    }

    @Test
    fun `评分这种非幂等 post 不切换`() {
        // 评分（POST /api/v1/articles/{id}/rate）写 feedback(type=rate)，
        // 那是**追加**一行、feedback 表没有唯一约束 → 重发会多一行埋点
        // （统计被灌水）→ 绝不能切。
        val reset = SocketException("Connection reset")
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(FallbackBaseUrlInterceptor(fallbackServer.url("/").toString()))
            .addInterceptor(Interceptor { chain ->
                if (calls.getAndIncrement() == 0) throw reset
                chainProceed(chain)
            })
            .build()

        val thrown = runCatching {
            client.newCall(
                Request.Builder().url(primaryServer.url("/api/v1/articles/art-1/rate"))
                    .post("{}".toRequestBody(null)).build()
            ).execute()
        }.exceptionOrNull()

        assertTrue("期望原样抛出，实际：$thrown", thrown is SocketException)
        assertEquals(0, fallbackServer.requestCount)
    }

    @Test
    fun `put 遇到连接被重置时切换`() {
        // 隐私同意走的是 PUT /api/v1/users/me/consent（后端「取或建 + 覆盖写」，
        // 完全幂等）。PUT 按 HTTP 规范就是幂等方法 → 该切。
        // 不切的后果：某个网络 RST 掉到域名的连接时，**App 卡在隐私同意页出不来**。
        val reset = SocketException("Connection reset")
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(FallbackBaseUrlInterceptor(fallbackServer.url("/").toString()))
            .addInterceptor(Interceptor { chain ->
                if (calls.getAndIncrement() == 0) throw reset
                chainProceed(chain)
            })
            .build()
        fallbackServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val code = client.newCall(
            Request.Builder().url(primaryServer.url("/api/v1/users/me/consent"))
                .put("{}".toRequestBody(null)).build()
        ).execute().use { it.code }

        assertEquals(200, code)
        assertEquals(1, fallbackServer.requestCount)
    }

    @Test
    fun `主地址失败一次后进入熔断，后续请求直接走备用地址`() {
        // 光有「切换」不够：主地址坏掉时每个请求都要先失败一次。
        // 熔断期内应该直接走备用地址 —— 省掉必然失败的那一次往返。
        val reset = SocketException("Connection reset")
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(FallbackBaseUrlInterceptor(fallbackServer.url("/").toString()))
            .addInterceptor(Interceptor { chain ->
                // 主地址（primaryServer）上的请求一律 reset，并计数
                // （两个 MockWebServer 同 host 不同端口，要用端口区分）
                if (chain.request().url.port == primaryServer.port) {
                    calls.incrementAndGet()
                    throw reset
                }
                chainProceed(chain)
            })
            .build()
        fallbackServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        fallbackServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        // 第一次：主地址失败 → 切备用
        client.newCall(Request.Builder().url(primaryServer.url("/a")).build())
            .execute().use { it.body!!.string() }
        // 第二次：熔断期内 → **不再试主地址**，直接走备用
        client.newCall(Request.Builder().url(primaryServer.url("/b")).build())
            .execute().use { it.body!!.string() }

        assertEquals(2, fallbackServer.requestCount)
        // 主地址只被试过第一次
        assertEquals(1, calls.get())
    }

    @Test
    fun `业务错误不切换`() {
        // 5xx 是服务端的回答，换地址没用；切了会把真实错误掩盖掉
        primaryServer.enqueue(MockResponse().setResponseCode(500))
        val client = client(fallbackServer.url("/").toString())

        val code = client.newCall(Request.Builder().url(primaryServer.url("/x")).build())
            .execute().use { it.code }

        assertEquals(500, code)
        assertEquals(0, fallbackServer.requestCount)
    }

    @Test
    fun `读超时：幂等请求会切到备用地址`() {
        // 2026-10-23 改判据。
        //
        // 原来「读超时一律不切」，理由是「服务端慢，换地址也慢」——
        // 但备用地址是**另一条网络路径**（域名 ←→ 直连 IP），
        // 不是同一个入口换个写法。
        //
        // 真机实测（小米 Mi 11，172.16.6.0/20）：同一网络对域名时好时坏 ——
        // 多数请求是「Connection reset」（切了，正常），
        // 而隐私政策那次是「Read timed out」（没切）→ 同意页卡死、App 不可用。
        // 幂等的 GET 重试一次没有副作用 → 该切。
        primaryServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        fallbackServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val client = OkHttpClient.Builder()
            .addInterceptor(FallbackBaseUrlInterceptor(fallbackServer.url("/").toString()))
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build()

        val body = client.newCall(Request.Builder().url(primaryServer.url("/x")).build())
            .execute().use { it.body!!.string() }

        assertEquals("ok", body)
        assertEquals(1, fallbackServer.requestCount)
    }

    @Test
    fun `读超时：不幂等的写操作不切`() {
        // 读超时说明连接已建立、请求**可能已经到达服务端**（它已经开始处理
        // 只是没回完）→ 重发等于把同一个操作提交两次
        // （"删除文章"这种操作会真删两次）→ 必须不切。
        primaryServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = OkHttpClient.Builder()
            .addInterceptor(FallbackBaseUrlInterceptor(fallbackServer.url("/").toString()))
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build()

        val thrown = runCatching {
            client.newCall(
                Request.Builder()
                    .url(primaryServer.url("/x"))
                    .post("{}".toRequestBody("text/plain".toMediaType()))
                    .build()
            ).execute()
        }.exceptionOrNull()

        assertTrue("期望读超时，实际：$thrown", thrown is SocketTimeoutException)
        assertEquals(0, fallbackServer.requestCount)
    }

    @Test
    fun `备用地址为空时不做任何事`() {
        primaryServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val client = client("")

        val body = client.newCall(Request.Builder().url(primaryServer.url("/x")).build())
            .execute().use { it.body!!.string() }

        assertEquals("ok", body)
        assertEquals(0, fallbackServer.requestCount)
    }

    @Test
    fun `备用地址非法时不崩`() {
        primaryServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val client = client("不是URL")

        val body = client.newCall(Request.Builder().url(primaryServer.url("/x")).build())
            .execute().use { it.body!!.string() }

        assertEquals("ok", body)
    }

}
