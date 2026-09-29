package com.tingxia.audio.auth

import android.content.Context
import com.tingxia.audio.ui.auth.AuthState
import com.tingxia.audio.ui.auth.AuthViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * 退出登录回归。
 *
 * 这个 bug 单测完全看不见、编译也过不了错 —— 它只在真机点下去才炸：
 * 设置页「退出登录」点了之后 App 直接退出，账户却还登着。真因是三个叠在一起的问题：
 *
 *  1. 导航图里根本没有 `login` 路由，`navigate("login")` 抛 IllegalArgumentException
 *     → 进程死亡（登录页是 AuthRoot 按 state 分支渲染的，不是 NavHost 的 destination）；
 *  2. 登出用的是 settings 作用域的 SettingsViewModel，它清 token 却改不动
 *     AuthRoot 持有的 AuthState，UI 仍停在已登录态；
 *  3. 那个 ViewModel 跟着当前 NavBackStackEntry 一起销毁，viewModelScope 在
 *     `api.logout()` 返回前就被取消；而 `runCatching` 连 CancellationException 一起吞，
 *     后面 `tokenManager.clear()` 在已取消的协程里于第一个挂起点再次抛出 ——
 *     本地 token 永远清不掉。
 *
 * 这里重点锁 3：无论服务端失败还是协程被取消，本地 token 都必须被清掉。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogoutTest {

    // AuthViewModel 走 viewModelScope（Dispatchers.Main.immediate），
    // 必须把它换成测试调度器，否则 Robolectric 的真实 looper 不受 runTest 控制，
    // 断言会变成"看谁跑得快"的时序赌博。
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** 假 TokenManager：全部走内存，不碰真实 DataStore。 */
    private class FakeTokenManager(context: Context) : TokenManager(context) {
        private var accessToken: String? = null
        private var refreshToken: String? = null
        private var userId: Long? = null
        var cleared = false
            private set

        override suspend fun getAccessToken(): String? = accessToken
        override suspend fun getRefreshToken(): String? = refreshToken
        override suspend fun getUserId(): Long? = userId

        override suspend fun saveTokens(access: String, refresh: String, userId: Long) {
            accessToken = access
            refreshToken = refresh
            this.userId = userId
        }

        override suspend fun clear() {
            // 必须带一个真实挂起点：真实实现是 DataStore 写盘（context.dataStore.edit），
            // 协程已取消时它会在挂起点抛 CancellationException。
            // 如果这里的 clear() 一路同步跑完，测试就抓不到"取消导致 token 清不掉"这个 bug。
            yield()
            cleared = true
            accessToken = null
            refreshToken = null
            userId = null
        }
    }

    /** 假 AuthApi：只有 logout 会被调用。 */
    private class FakeAuthApi(
        private val onLogout: suspend () -> Unit = {},
    ) : AuthApi {
        override suspend fun wechatLogin(req: WechatLoginRequest): AuthResponse = error("not used")
        override suspend fun refresh(req: RefreshRequest): AuthResponse = error("not used")
        override suspend fun logout(): LogoutResponse {
            onLogout()
            return LogoutResponse()
        }

        override suspend fun me(): UserInfoResponse = error("not used")
        override suspend fun issueToken(req: TokenIssueRequest): TokenIssueResponse = error("not used")
    }

    private fun newTokenManager() = FakeTokenManager(RuntimeEnvironment.getApplication())

    @Test
    fun `正常登出会清掉本地 token`() = runTest {
        val tokenManager = newTokenManager()
        val repo = AuthRepository(FakeAuthApi(), tokenManager)

        repo.logout()

        assertTrue("本地 token 必须被清空", tokenManager.cleared)
    }

    @Test
    fun `服务端注销失败时本地 token 仍要清掉`() = runTest {
        val tokenManager = newTokenManager()
        val repo = AuthRepository(
            FakeAuthApi { throw IOException("connection reset") },
            tokenManager,
        )

        repo.logout() // 不应抛出

        assertTrue("服务端挂了也不能让用户登不出去", tokenManager.cleared)
    }

    @Test
    fun `协程被取消时本地 token 仍要清掉`() = runTest {
        val gate = CompletableDeferred<Unit>() // 永不完成，模拟卡住的网络请求
        val tokenManager = newTokenManager()
        val repo = AuthRepository(FakeAuthApi { gate.await() }, tokenManager)

        val job = launch { repo.logout() }
        runCurrent() // 让协程跑到 api.logout() 里挂住
        job.cancelAndJoin() // 模拟页面销毁 → viewModelScope 被取消

        assertTrue(
            "协程被取消也必须清掉本地 token（这是 '点了没反应' 的根因）",
            tokenManager.cleared,
        )
        gate.complete(Unit)
    }

    @Test
    fun `AuthViewModel 登出后状态切到 NotLoggedIn`() = runTest(dispatcher) {
        val tokenManager = newTokenManager()
        val repo = AuthRepository(FakeAuthApi(), tokenManager)
        val viewModel = AuthViewModel(repo)

        // 先置成已登录
        tokenManager.saveTokens("access", "refresh", 42L)
        viewModel.checkLogin()
        advanceUntilIdle()
        assertEquals(AuthState.LoggedIn(42L), viewModel.state.value)

        viewModel.logout()
        advanceUntilIdle()

        // 状态必须真正翻过去 —— AuthRoot 靠这个分支渲染 LoginScreen，
        // 它不翻就还是已登录态（设置页原来就是这样"清了 token 却还登着"）。
        assertEquals(AuthState.NotLoggedIn, viewModel.state.value)
        assertTrue(tokenManager.cleared)
    }
}
