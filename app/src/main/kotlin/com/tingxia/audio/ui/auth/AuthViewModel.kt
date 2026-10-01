package com.tingxia.audio.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.auth.AuthRepository
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
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
) : ViewModel() {

    private val _state = MutableStateFlow<AuthState>(AuthState.Checking)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** 启动检查本地 token，决定首屏路由。 */
    fun checkLogin() {
        viewModelScope.launch {
            val token = authRepository.getAccessToken()
            val userId = authRepository.getUserId()
            _state.value = if (token != null && userId != null) {
                AuthState.LoggedIn(userId)
            } else {
                AuthState.NotLoggedIn
            }
        }
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
                _state.value = AuthState.LoggedIn(resp.userId)
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
                _state.value = AuthState.LoggedIn(resp.userId)
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
            authRepository.logout()
            _state.value = AuthState.NotLoggedIn
        }
    }
}

sealed interface AuthState {
    data object Checking : AuthState
    data object NotLoggedIn : AuthState
    data object Loading : AuthState
    data class LoggedIn(val userId: Long) : AuthState
    data class Error(val message: String) : AuthState
}
