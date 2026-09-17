package com.tingxia.audio.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.auth.AuthRepository
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

    /** 微信登录（CP4.6 mock：直接返回固定 token）。 */
    fun mockWechatLogin() {
        viewModelScope.launch {
            _state.value = AuthState.Loading
            try {
                val resp = authRepository.mockWechatLogin() // mock，CP4.7 接真 OAuth
                _state.value = AuthState.LoggedIn(resp.userId)
            } catch (e: Exception) {
                _state.value = AuthState.Error(e.message ?: "unknown error")
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
