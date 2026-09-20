package com.tingxia.audio.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 引导页状态机：驱动 OnboardingScreen 3 步流程。
 *
 * 状态：[OnboardingState]
 * - [OnboardingState.Idle] 初始
 * - [OnboardingState.InProgress] 进行中（携带当前步数 1-3）
 * - [OnboardingState.Completed] 已完成
 * - [OnboardingState.Error] 出错
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repository: OnboardingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Idle)
    val state: StateFlow<OnboardingState> = _state

    fun onStart() {
        _state.value = OnboardingState.InProgress(currentStep = 1)
        viewModelScope.launch {
            // CP7.4 修复:soft-warn 模式 — backend 暂时不可达不应卡死 onboarding UI。
            runCatching { repository.start() }
                .onSuccess { _state.value = OnboardingState.InProgress(currentStep = 1) }
                .onFailure {
                    android.util.Log.w("OnboardingViewModel", "onStart failed (soft-warn): ${it.message}")
                    _state.value = OnboardingState.InProgress(currentStep = 1)
                }
        }
    }

    fun onStepViewed(step: Int) {
        viewModelScope.launch {
            // CP7.4 修复:stepViewed 始终软失败,不切 state。
            runCatching { repository.stepViewed(step) }
                .onFailure {
                    android.util.Log.w("OnboardingViewModel", "stepViewed failed (soft-warn): ${it.message}")
                }
        }
    }

    fun onComplete(onSuccess: () -> Unit) {
        viewModelScope.launch {
            // CP7.4 修复:onComplete 也改 soft-warn + fire-and-forget 语义。
            // 本地 has_onboarded SharedPreferences 是 client-side 真值,
            // backend /onboarding/done 只是遥测埋点,不应阻塞用户进入首页。
            runCatching { repository.complete() }
                .onSuccess {
                    _state.value = OnboardingState.Completed
                    onSuccess()
                }
                .onFailure {
                    android.util.Log.w("OnboardingViewModel", "onComplete failed (soft-warn): ${it.message}")
                    _state.value = OnboardingState.Completed
                    onSuccess()
                }
        }
    }
}

sealed interface OnboardingState {
    data object Idle : OnboardingState
    data class InProgress(val currentStep: Int) : OnboardingState
    data object Completed : OnboardingState
    data class Error(val message: String) : OnboardingState
}
