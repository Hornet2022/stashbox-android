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
            runCatching { repository.start() }
                .onSuccess { _state.value = OnboardingState.InProgress(currentStep = 1) }
                .onFailure { _state.value = OnboardingState.Error(it.message ?: "Unknown error") }
        }
    }

    fun onStepViewed(step: Int) {
        viewModelScope.launch {
            runCatching { repository.stepViewed(step) }
                .onFailure { /* 静默失败：埋点失败不影响 UI 流程 */ }
        }
    }

    fun onComplete(onSuccess: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.complete() }
                .onSuccess {
                    _state.value = OnboardingState.Completed
                    onSuccess()
                }
                .onFailure { _state.value = OnboardingState.Error(it.message ?: "Unknown error") }
        }
    }
}

sealed interface OnboardingState {
    data object Idle : OnboardingState
    data class InProgress(val currentStep: Int) : OnboardingState
    data object Completed : OnboardingState
    data class Error(val message: String) : OnboardingState
}
