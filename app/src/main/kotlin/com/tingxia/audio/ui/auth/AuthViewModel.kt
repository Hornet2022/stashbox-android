package com.tingxia.audio.ui.auth

import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.BuildConfig
import com.tingxia.audio.auth.AuthRepository
import com.tingxia.audio.auth.SessionExpirySignal
import com.tingxia.audio.data.repository.DeviceRepository
import com.tingxia.audio.ui.AUTH_EXPIRED_MESSAGE
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 鉴权状态机：驱动 MainActivity 在 登录页 / 列表页 之间路由。
 *
 * 状态：[AuthState]
 * - [AuthState.Checking] 启动检查中
 * - [AuthState.NotLoggedIn] 无 token
 * - [AuthState.Loading] 登录进行中
 * - [AuthState.LoggedIn] 已登录（携带 userId）
 * - [AuthState.Error] 出错
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    /**
     * 推送设备注册（登录后登记本机，登出前解绑）。
     *
     * 声明成**可空 + 默认值**：单测里有 `AuthViewModel(repo)` 这样的直接构造
     * （见 auth/LogoutTest.kt、auth/AuthExpiryClassificationTest.kt），
     * 加一个必填参数会把那些不属于本次改动的测试文件一起改坏。
     * Hilt 侧照常注入（AppUpdateModule 里有 DeviceRepository 的绑定）。
     */
    private val deviceRepository: DeviceRepository? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<AuthState>(AuthState.Checking)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /**
     * 会话失效事件：access 401 且 refresh 也换不回 token 时，
     * [com.tingxia.audio.auth.AuthInterceptor] 清完 token 就会发这个。
     *
     * 暴露成 flow 而不是在本类 init 里偷偷订阅：谁在观察 [state]，谁负责把事件
     * 翻译成状态变化（UI 侧见 MainActivity 的 AuthRoot）。这样"会话归谁管"这件事
     * 只剩一个答案——状态只在这里改。
     */
    val sessionExpired: SharedFlow<Unit> = SessionExpirySignal.events

    /** 启动检查本地 token，决定首屏路由。 */
    fun checkLogin() {
        viewModelScope.launch {
            val token = authRepository.getAccessToken()
            // userId 必须是真的。后端 users.id 是自增主键（从 1 开始），0 / 负数
            // 只能是解析失败留下的哨兵；放过它 = 把用户当已登录，而配额 / 收藏 /
            // 归属全记到共享的 user-0 桶，且界面上完全看不出异常。
            // 老版本已经写脏的 0 也会在这里被挡下 → 走一次正常登录即修复。
            val userId = authRepository.getUserId()?.takeIf { it > 0L }
            if (token != null && userId != null) {
                _state.value = AuthState.LoggedIn(userId)
                // 已经是登录态（App 重启后）→ 补一次设备登记。
                // 推送投递目标会随 App 版本变化（app_version / device_model 会被刷新），
                // 而且重装后本机 id 会变 —— 只在"刚登录"时登记会漏掉这些情况。
                registerDeviceForPush()
            } else {
                _state.value = AuthState.NotLoggedIn
            }
        }
    }

    /**
     * 会话在后台被判定失效 → 翻回登录页，并把原因显示在登录页上。
     *
     * 这里**不再清 token**：清 token 是发信号那一端的职责（拦截器清完才发信号），
     * 两处都清只会让"到底谁拥有会话"更模糊——而这正是这组 bug 的根因。
     *
     * 幂等：并发 401 可能重复发信号，重复置成同一个状态没有副作用。
     */
    fun onSessionExpired() {
        _state.value = AuthState.Error(AUTH_EXPIRED_MESSAGE)
    }

    /**
     * 真实登录：走 `wechat-login`（唯一上线路径）。
     *
     * [code] 在接真微信 OAuth 前传稳定串即可（服务端按 `wx_<code>` 查/建用户）。
     */
    fun login(code: String) {
        viewModelScope.launch {
            _state.value = AuthState.Loading
            try {
                val resp = authRepository.wechatLogin(code)
                // 登录成功 → 登记本机为推送设备（失败不影响登录本身）。
                registerDeviceForPush()
                _state.value = loggedInOrError(resp.userId)
            } catch (e: Exception) {
                _state.value = AuthState.Error(friendlyError(e, fallback = "登录失败，请稍后再试"))
            }
        }
    }

    /**
     * 联调登录：以指定 user_id 换取 token（debug 包专用）。
     *
     * 依赖 gateway 的 dev-only 端点，服务端默认关闭 → 未开启时返回 403，
     * 这里把它翻译成一句能照做的提示，而不是甩个 HTTP 错误给用户。
     */
    fun devLoginAs(userId: String) {
        viewModelScope.launch {
            _state.value = AuthState.Loading
            try {
                val resp = authRepository.devImpersonate(userId)
                registerDeviceForPush()
                _state.value = loggedInOrError(resp.userId)
            } catch (e: Exception) {
                val msg = friendlyError(e, fallback = "联调登录失败")
                _state.value = AuthState.Error(
                    if (msg.contains("403") || msg.contains("dev token")) {
                        "联调登录被服务端拒绝：gateway 未开启 dev token 端点。\n" +
                            "如需在真机上冒充指定账号，请让服务端设 STASHBOX_ALLOW_DEV_TOKEN=1 后重启。"
                    } else {
                        msg
                    }
                )
            }
        }
    }

    /** 登出：清空本地 token，回到登录页。 */
    fun logout() {
        viewModelScope.launch {
            // ⚠️ 顺序：先解绑推送设备，再登出。
            // 解绑接口需要鉴权，token 一清就调不通了 —— 那样共享设备上会残留
            // 上一个账号的推送登记（厂商通道一旦接上就可能把上一个账号的消息
            // 推给这台手机）。解绑失败也不能挡住登出，所以这里吞掉异常。
            unregisterDeviceForPush()
            authRepository.logout()
            _state.value = AuthState.NotLoggedIn
        }
    }

    /**
     * 登录后登记本机为推送设备。
     *
     * 刻意**不阻塞**登录：推送登记是"锦上添花"，一个网络抖动不该让用户
     * 卡在"登录中"。失败只记日志。
     */
    private fun registerDeviceForPush() {
        val repo = deviceRepository ?: return
        viewModelScope.launch {
            runCatching {
                repo.registerCurrentDevice(
                    appVersion = BuildConfig.VERSION_NAME,
                    deviceModel = Build.MODEL,
                )
            }.onFailure {
                Log.w(TAG, "推送设备登记失败：${it.message}")
            }
        }
    }

    /**
     * 登出前解绑本机。
     *
     * 是 suspend 且**顺序等待**：调用方必须 await 它，
     * 否则会跟 [AuthRepository.logout] 抢 token（token 先被清掉，
     * 解绑请求就变成 401，共享设备上的推送登记会留在上一个账号名下）。
     */
    private suspend fun unregisterDeviceForPush() {
        val repo = deviceRepository ?: return
        runCatching { repo.unregisterCurrentDevice() }
            .onFailure { Log.w(TAG, "推送设备解绑失败：${it.message}") }
    }

    /**
     * 账号已在服务端注销 → 本机已清空 → 回到登录页。
     *
     * ## 为什么不复用 [logout]
     *
     * [logout] 会去调服务端的 `POST /api/v1/auth/logout`。可注销之后账号已经
     * 没了、token 也被全部吊销，那一次请求只会换来 401，
     * 紧接着冒出"登录已失效，请重新登录" —— 用户**主动**注销，却看到一句
     * "登录已失效"，是纯噪声，还容易被当成 bug 报上来。
     *
     * 所以注销链路自己清本机（见
     * [com.tingxia.audio.data.repository.AccountRepository.logoutLocalOnly]），
     * 这里只负责把界面翻回登录页 —— 状态只有这一处能改。
     */
    fun onAccountDeleted() {
        _state.value = AuthState.NotLoggedIn
    }

    /**
     * 登录成功的落点：**只有真 userId 才算登录成功**。
     *
     * `wechatLogin` 对非数字 user_id 已经抛异常，但 `devImpersonate` 是从 JWT 里
     * 解析 `sub` 的，解析不出来会返回 0。0 若被当成正常会话写进 [AuthState.LoggedIn]，
     * 用户就会带着一个共享的假身份继续用（且下次冷启动 [checkLogin] 也认它）。
     * 这里挡掉并给出可照做的提示。
     */
    private fun loggedInOrError(userId: Long): AuthState =
        if (userId > 0L) {
            AuthState.LoggedIn(userId)
        } else {
            AuthState.Error("账号信息无效（用户 ID 无法识别），请重新登录")
        }

    private companion object {
        const val TAG = "AuthViewModel"
    }
}

sealed interface AuthState {
    data object Checking : AuthState
    data object NotLoggedIn : AuthState
    data object Loading : AuthState
    data class LoggedIn(val userId: Long) : AuthState
    data class Error(val message: String) : AuthState
}
