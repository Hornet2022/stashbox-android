package com.tingxia.audio.ui.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.model.QuotaResponse
import com.tingxia.audio.data.repository.QuotaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * CP11.0.4 P1.2 付费墙 ViewModel:
 * - refresh(): 拉一次当前配额显示
 * - mockUpgrade(): 后端无真实支付,模拟 800ms 后回调 onUpgraded()
 */
@HiltViewModel
class PaywallViewModel @Inject constructor(
    private val repository: QuotaRepository,
) : ViewModel() {

    data class UiState(
        val quota: QuotaResponse? = null,
        val isLoading: Boolean = false,
        val isUpgrading: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val quota = repository.getQuota()
                _state.value = _state.value.copy(quota = quota, isLoading = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "加载配额失败",
                )
            }
        }
    }

    /**
     * Mock 升级:
     * 后端实际无支付接口(plan/premium 由 admin-web 配置),这里只模拟"升级成功" → 回调。
     * 真接入 Stripe/Apple IAP 时再换实现。
     */
    fun mockUpgrade(onUpgraded: () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isUpgrading = true)
            delay(800)
            _state.value = _state.value.copy(isUpgrading = false)
            onUpgraded()
        }
    }
}