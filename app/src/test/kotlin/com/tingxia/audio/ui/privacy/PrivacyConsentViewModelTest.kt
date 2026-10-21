package com.tingxia.audio.ui.privacy

import com.tingxia.audio.auth.AuthApi
import com.tingxia.audio.auth.AuthRepository
import com.tingxia.audio.auth.LogoutResponse
import com.tingxia.audio.auth.RefreshRequest
import com.tingxia.audio.auth.TokenIssueRequest
import com.tingxia.audio.auth.TokenIssueResponse
import com.tingxia.audio.auth.TokenManager
import com.tingxia.audio.auth.UserInfoResponse
import com.tingxia.audio.auth.WechatLoginRequest
import com.tingxia.audio.auth.AuthResponse
import com.tingxia.audio.data.local.UserDataCleaner
import com.tingxia.audio.data.remote.ConsentApi
import com.tingxia.audio.data.remote.ConsentStateResponse
import com.tingxia.audio.data.remote.ConsentUpdateRequest
import com.tingxia.audio.data.remote.PrivacyPolicyResponse
import com.tingxia.audio.data.repository.ConsentLocalStore
import com.tingxia.audio.data.repository.ConsentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * 隐私政策同意闸门的单测（2026-10-21）。
 *
 * ## 这里锁什么
 *
 * - **没同意就不放行**：闸门在用户点「同意并继续」之前绝不进入 AGREED；
 * - **409 要重新拉政策，而不是拿新版本偷偷重试**：用户没看过 v2，
 *   替他同意 v2 不是同意，是代签；
 * - **已同意当前版本就别再烦用户**；
 * - **政策改版后必须重新征询同意**（同意的是哪一版，比"有没有点过同意"更重要）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivacyConsentViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── 假实现 ──

    /**
     * 假 ConsentApi。
     *
     * `policyResponses` 按调用顺序返回 —— 409 之后要能返回"新版政策"，
     * 才能验证客户端确实重新拉了而不是拿着旧版本重试。
     */
    private class FakeConsentApi(
        val policyResponses: List<PrivacyPolicyResponse> = listOf(
            PrivacyPolicyResponse(version = "v1", updated_at = "2026-10-21", title = "听匣隐私政策", content = "# 标题\n\n正文"),
        ),
        val consentResponse: ConsentStateResponse = ConsentStateResponse(),
        val updateResponse: ConsentStateResponse = ConsentStateResponse(
            privacy_policy_version = "v1",
            privacy_policy_accepted = true,
        ),
        val updateError: Throwable? = null,
    ) : ConsentApi {
        var policyFetchCount = 0
        var consentFetchCount = 0
        val updateRequests = mutableListOf<ConsentUpdateRequest>()

        override suspend fun privacyPolicy(): PrivacyPolicyResponse {
            val index = (policyFetchCount).coerceAtMost(policyResponses.size - 1)
            policyFetchCount++
            return policyResponses[index]
        }

        override suspend fun consentState(): ConsentStateResponse {
            consentFetchCount++
            return consentResponse
        }

        override suspend fun updateConsent(body: ConsentUpdateRequest): ConsentStateResponse {
            updateRequests += body
            updateError?.let { throw it }
            return updateResponse
        }
    }

    /** 假 AuthApi：同意闸门这条路上一个都用不到。 */
    private class FakeAuthApi : AuthApi {
        override suspend fun wechatLogin(req: WechatLoginRequest): AuthResponse = error("not used")
        override suspend fun refresh(req: RefreshRequest): AuthResponse = error("not used")
        override suspend fun logout(): LogoutResponse = error("not used")
        override suspend fun me(): UserInfoResponse = error("not used")
        override suspend fun issueToken(req: TokenIssueRequest): TokenIssueResponse = error("not used")
    }

    /** 假 TokenManager：全内存，只提供 userId。 */
    private class FakeTokenManager(
        context: android.content.Context,
        private val userId: Long? = 42L,
    ) : TokenManager(context) {
        override suspend fun getAccessToken(): String? = null
        override suspend fun getRefreshToken(): String? = null
        override suspend fun getUserId(): Long? = userId
        override suspend fun saveTokens(access: String, refresh: String, userId: Long) = Unit
        override suspend fun clear() = Unit
    }

    /** 假 UserDataCleaner：同意闸门这条路上用不到，只为构造 AuthRepository。 */
    private class FakeUserDataCleaner : UserDataCleaner {
        override suspend fun clearAll() = Unit
    }

    private fun vm(
        api: FakeConsentApi,
        userId: Long? = 42L,
    ): PrivacyConsentViewModel {
        val context = RuntimeEnvironment.getApplication()
        return PrivacyConsentViewModel(
            repository = ConsentRepository(api),
            localStore = ConsentLocalStore(context),
            authRepository = AuthRepository(
                FakeAuthApi(),
                FakeTokenManager(context, userId),
                FakeUserDataCleaner(),
            ),
        )
    }

    /**
     * 造一个**真的**后端 409 业务信封异常。
     *
     * 为什么这么绕：okhttp 4.x 没有公开的 `Response.error(code, body)` 工厂，
     * 而 `retrofit2.HttpException` 必须拿一个真实的 okhttp Response ——
     * 所以这里用 MockWebServer 真跑一次 409，让 Retrofit 自己抛。
     *
     * 好处是这条链路完全真实：`parseEnvelope` 真的会读到那份 409 信封，
     * 业务码 40900 是从响应体里解析出来的，不是手写常量。
     */
    private fun conflict(): HttpException {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":40900,"message":"policy version mismatch","data":null}"""),
        )
        server.start()
        try {
            val json = Json { ignoreUnknownKeys = true }
            val retrofit = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(
                    json.asConverterFactory("application/json".toMediaType()),
                )
                .build()
            val api = retrofit.create(ConsentApi::class.java)
            return runBlocking {
                try {
                    api.updateConsent(ConsentUpdateRequest("v1", accepted = true))
                    throw AssertionError("应当返回 409")
                } catch (e: HttpException) {
                    e
                }
            }
        } finally {
            server.shutdown()
        }
    }

    // ── 用例 ──

    @Test
    fun `未同意时闸门不放行`() = testScope.runTest {
        val api = FakeConsentApi(
            // 服务端明确说"没同意"
            consentResponse = ConsentStateResponse(privacy_policy_accepted = false),
        )
        val vm = vm(api)
        advanceUntilIdle()

        assertEquals(
            "没同意就必须停在待确认状态",
            PrivacyConsentViewModel.GateStatus.NEEDS_CONSENT,
            vm.uiState.value.status,
        )
        assertNotNull("政策正文必须先拉下来给用户看", vm.uiState.value.policy)
        assertTrue(
            "没点同意就不该发任何写请求",
            api.updateRequests.isEmpty(),
        )
    }

    @Test
    fun `同意成功后闸门放行并记下版本`() = testScope.runTest {
        val api = FakeConsentApi(
            consentResponse = ConsentStateResponse(privacy_policy_accepted = false),
            updateResponse = ConsentStateResponse(
                privacy_policy_version = "v1",
                privacy_policy_accepted = true,
            ),
        )
        val vm = vm(api)
        advanceUntilIdle()

        vm.agree()
        advanceUntilIdle()

        assertEquals(
            PrivacyConsentViewModel.GateStatus.AGREED,
            vm.uiState.value.status,
        )
        assertEquals(
            "提交的版本必须是界面上正在显示的那一版",
            listOf("v1"),
            api.updateRequests.map { it.privacy_policy_version },
        )
        assertTrue("必须真的记了同意", api.updateRequests.single().accepted)
    }

    @Test
    fun `服务端返回 409 时重新拉政策而不是拿新版本重试`() = testScope.runTest {
        val api = FakeConsentApi(
            policyResponses = listOf(
                PrivacyPolicyResponse(version = "v1", content = "旧版正文"),
                PrivacyPolicyResponse(version = "v2", content = "新版正文"),
            ),
            consentResponse = ConsentStateResponse(privacy_policy_accepted = false),
            updateError = conflict(),
        )
        val vm = vm(api)
        advanceUntilIdle()
        assertEquals("v1", vm.uiState.value.policy?.version)

        vm.agree()
        advanceUntilIdle()

        assertEquals(
            "409 之后必须重新拉一次政策",
            2,
            api.policyFetchCount,
        )
        assertEquals(
            "绝不能拿新版本号自动重试 —— 那等于替用户同意了他没看过的版本",
            1,
            api.updateRequests.size,
        )
        assertEquals(
            PrivacyConsentViewModel.GateStatus.NEEDS_CONSENT,
            vm.uiState.value.status,
        )
        assertEquals(
            "界面上必须换成新版本的政策",
            "v2",
            vm.uiState.value.policy?.version,
        )
        assertNotNull("必须告诉用户政策更新了", vm.uiState.value.notice)
    }

    @Test
    fun `已同意当前版本就直接放行`() = testScope.runTest {
        val api = FakeConsentApi(
            consentResponse = ConsentStateResponse(
                privacy_policy_version = "v1",
                privacy_policy_accepted = true,
            ),
        )
        val vm = vm(api)
        advanceUntilIdle()

        assertEquals(
            PrivacyConsentViewModel.GateStatus.AGREED,
            vm.uiState.value.status,
        )
        assertTrue("已经同意过就不该再发写请求", api.updateRequests.isEmpty())
    }

    @Test
    fun `政策改版后必须重新征询同意`() = testScope.runTest {
        val api = FakeConsentApi(
            // 服务端记的是"用户同意过 v1"，但当前政策已经是 v2
            policyResponses = listOf(
                PrivacyPolicyResponse(version = "v2", content = "新版正文"),
            ),
            consentResponse = ConsentStateResponse(
                privacy_policy_version = "v1",
                privacy_policy_accepted = true,
            ),
        )
        val vm = vm(api)
        advanceUntilIdle()

        assertEquals(
            "同意的是 v1、服务端现在是 v2 —— 必须重新问一次",
            PrivacyConsentViewModel.GateStatus.NEEDS_CONSENT,
            vm.uiState.value.status,
        )
    }

    @Test
    fun `拉不到同意状态时本地记录与服务端版本一致才放行`() = testScope.runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = ConsentLocalStore(context)
        store.setAcceptedVersion(42L, "v1")

        val api = object : ConsentApi {
            override suspend fun privacyPolicy() =
                PrivacyPolicyResponse(version = "v1", content = "正文")

            override suspend fun consentState(): ConsentStateResponse =
                throw java.io.IOException("network down")

            override suspend fun updateConsent(body: ConsentUpdateRequest) =
                error("not used")
        }
        val vm = PrivacyConsentViewModel(
            repository = ConsentRepository(api),
            localStore = store,
            authRepository = AuthRepository(
                FakeAuthApi(),
                FakeTokenManager(context, 42L),
                FakeUserDataCleaner(),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            "本地记的版本与服务端当前版本一致，可以放行",
            PrivacyConsentViewModel.GateStatus.AGREED,
            vm.uiState.value.status,
        )
    }

    @Test
    fun `拉不到同意状态且本地版本对不上时不放行`() = testScope.runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = ConsentLocalStore(context)
        store.setAcceptedVersion(42L, "v1")

        val api = object : ConsentApi {
            override suspend fun privacyPolicy() =
                PrivacyPolicyResponse(version = "v2", content = "新版正文")

            override suspend fun consentState(): ConsentStateResponse =
                throw java.io.IOException("network down")

            override suspend fun updateConsent(body: ConsentUpdateRequest) =
                error("not used")
        }
        val vm = PrivacyConsentViewModel(
            repository = ConsentRepository(api),
            localStore = store,
            authRepository = AuthRepository(
                FakeAuthApi(),
                FakeTokenManager(context, 42L),
                FakeUserDataCleaner(),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            "政策已经改成 v2 了，不能拿本地那条 v1 的记录放行",
            PrivacyConsentViewModel.GateStatus.FAILED,
            vm.uiState.value.status,
        )
    }
}
