package com.tingxia.audio.onboarding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * OnboardingViewModel 状态机 + onboarding 三个端点的真实调用回归。
 *
 * 两次吃过的暗亏：
 *
 * ① 埋点是软失败（CP7.4）。`/onboarding/start|step|done` 只是埋点，本地
 *    `has_onboarded` 才是 client-side 真值 —— 所以网络挂了也不能把用户卡在
 *    引导页。这一层一旦被改成「失败就切 Error」，按钮会被 `enabled = state !is Error`
 *    禁用，用户既走不出去也看不到原因（没有重试按钮、没有错误文案）。
 *
 * ② 响应类缺 `@Serializable`（commit 06a185c）。Retrofit 用的是
 *    `retrofit-converter-kotlinx-serialization`，找不到 serializer 时它在
 *    **构造请求那一刻**就抛 `Unable to create converter for class java.lang.Object`
 *    —— 请求压根没发出去。而 VM 把异常当 soft-warn 吞掉，功能表现是「点了没反应」，
 *    日志里只有一行 W，`step` / `done` 两个端点从未成功调用过，
 *    后端永远不知道用户完成了引导 → 用户每次冷启动可能又被弹一次引导。
 *
 * ② 单测层面验不到「服务端收到了」这件事，除非真把 Retrofit + MockWebServer
 *    接上，所以后半段用例是这么做的 —— 只 mock 掉 API 接口的话，
 *    缺 `@Serializable` 这类错误在测试里根本不会浮现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var api: FakeOnboardingApi
    private lateinit var vm: OnboardingViewModel

    @Before
    fun setup() {
        // onStart/onStepViewed/onComplete 的失败分支走 android.util.Log，需要 Android runtime
        Dispatchers.setMain(testDispatcher)
        api = FakeOnboardingApi()
        vm = OnboardingViewModel(OnboardingRepository(api))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun run(body: suspend TestScope.() -> Unit) = testScope.runTest { body() }

    // ── 状态机 ───────────────────────────────────────────────────────────────

    @Test
    fun `初始状态是 Idle`() {
        assertTrue(vm.state.value is OnboardingState.Idle)
    }

    @Test
    fun `onStart 进入第 1 步并打 start 埋点`() = run {
        vm.onStart()
        testScheduler.advanceUntilIdle()

        assertEquals(OnboardingState.InProgress(1), vm.state.value)
        assertEquals(1, api.startCalls)
    }

    @Test
    fun `start 埋点失败仍然停在第 1 步而不是 Error`() = run {
        // 后端不可达不该卡死引导 UI；一旦切成 Error，底部按钮被
        // `enabled = state !is Error` 禁用，用户就走不出去了
        api.failStart = true

        vm.onStart()
        testScheduler.advanceUntilIdle()

        assertEquals(OnboardingState.InProgress(1), vm.state.value)
        assertTrue(vm.state.value !is OnboardingState.Error)
    }

    @Test
    fun `stepViewed 按传入的步数打点`() = run {
        vm.onStepViewed(1)
        vm.onStepViewed(2)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(1, 2), api.viewedSteps)
    }

    @Test
    fun `stepViewed 失败不切 state 也不中断后续埋点`() = run {
        // 引导页每翻一步都调 stepViewed，一次失败若把状态搞脏，
        // 用户会看到进度条停住、按钮变灰
        api.failStep = true

        vm.onStepViewed(2)
        vm.onStepViewed(3)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(2, 3), api.viewedSteps)
        assertTrue(vm.state.value is OnboardingState.Idle)
    }

    @Test
    fun `onComplete 成功时进 Completed 并回调`() = run {
        var completed = false

        vm.onComplete(onSuccess = { completed = true })
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value is OnboardingState.Completed)
        assertTrue("onSuccess 应被调用", completed)
        assertEquals(1, api.completeCalls)
    }

    @Test
    fun `complete 埋点失败也照样 Completed 并回调`() = run {
        // done 端点只是遥测。本地 has_onboarded 才是真值，
        // 这里失败必须放行 —— 否则用户看完成功引导却进不了首页
        api.failComplete = true
        var completed = false

        vm.onComplete(onSuccess = { completed = true })
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value is OnboardingState.Completed)
        assertTrue("埋点失败不应吞掉完成回调", completed)
        assertEquals(1, api.completeCalls)
    }

    // ── 端点真的发得出去（06a185c 的回归）────────────────────────────────────

    @Test
    fun `step 端点真的发出去了并且响应能解析`() = run {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"step_viewed":2,"step_name":"选择兴趣"}""")
        )
        try {
            val realApi = makeOnboardingApi(server)

            val resp = realApi.stepViewed(2)

            assertEquals(2, resp.step_viewed)
            assertEquals("选择兴趣", resp.step_name)
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/api/v1/users/me/onboarding/step?step=2", req.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `done 端点真的发出去了并且响应能解析`() = run {
        // 缺 @Serializable 时这一条会在**构造请求那一刻**抛
        // "Unable to create converter for class java.lang.Object"，请求根本没上线
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"onboarding_done":true,"onboarding_done_at":"2026-10-03T12:00:00Z"}""")
        )
        try {
            val realApi = makeOnboardingApi(server)

            val resp = realApi.complete()

            assertTrue(resp.onboarding_done)
            assertEquals("2026-10-03T12:00:00Z", resp.onboarding_done_at)
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/api/v1/users/me/onboarding/done", req.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `start 端点无响应体也不该炸`() = run {
        // start 返回 Unit，Retrofit 对空响应体走 Unit 转换器；
        // 这条锁住「start 没被误加响应类」这类反向改动
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(204))
        try {
            makeOnboardingApi(server).start()

            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/api/v1/users/me/onboarding/start", req.path)
        } finally {
            server.shutdown()
        }
    }

    /** 与 [com.tingxia.audio.di.NetworkModule] 用同一个 converter 工厂。 */
    private fun makeOnboardingApi(server: MockWebServer): OnboardingApi {
        val json = Json { ignoreUnknownKeys = true }
        return Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OnboardingApi::class.java)
    }
}

/**
 * OnboardingApi 替身：记录埋点调用，并按需抛错模拟后端不可达。
 *
 * 三个开关各自对应一种失败形态：`failStep` / `failComplete` 抛的正是 06a185c
 * 时期日志里那行 `Unable to create converter` —— 保留这个措辞是为了让
 * 「软失败」用例读起来就是当时真实的现场。
 */
private class FakeOnboardingApi : OnboardingApi {

    var startCalls = 0
        private set

    /** 被调用过的 step 序列（按调用顺序）。 */
    val viewedSteps = mutableListOf<Int>()

    var completeCalls = 0
        private set

    var failStart = false
    var failStep = false
    var failComplete = false

    override suspend fun start() {
        startCalls++
        if (failStart) throw IllegalStateException("backend unreachable (fake)")
    }

    override suspend fun stepViewed(step: Int): StepResponse {
        viewedSteps += step
        if (failStep) throw IllegalStateException("Unable to create converter (fake)")
        return StepResponse(step_viewed = step, step_name = "step_$step")
    }

    override suspend fun complete(): DoneResponse {
        completeCalls++
        if (failComplete) throw IllegalStateException("Unable to create converter (fake)")
        return DoneResponse(onboarding_done = true, onboarding_done_at = "2026-10-03T12:00:00Z")
    }
}