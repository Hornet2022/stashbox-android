package com.tingxia.audio.ui.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.model.QuotaResponse
import com.tingxia.audio.data.repository.QuotaRepository
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * CP11.0.4 P1.2 付费墙 ViewModel:
 * - refresh(): 拉一次当前配额显示
 *
 * CP-QUOTA-HONEST：原来的 mockUpgrade() 已被删除。它 delay(800) 后直接回调
 * "升级成功"，但后端**没有任何支付端点**（orders 表无 migration，业务代码从不
 * 写它），配额一个字节都没变——用户以为付了钱，实际什么都没发生。
 * 付费墙改为如实说明"自助升级未开放"，等真接上支付再实现。
 */
@HiltViewModel
class PaywallViewModel @Inject constructor(
    private val repository: QuotaRepository,
) : ViewModel() {

    data class UiState(
        val quota: QuotaResponse? = null,
        val isLoading: Boolean = false,
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
                    error = friendlyError(e, fallback = "加载配额失败"),
                )
            }
        }
    }
}