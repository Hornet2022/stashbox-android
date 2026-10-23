package com.tingxia.audio.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.remote.SubscriptionFeedItem
import com.tingxia.audio.data.repository.SubscriptionRepository
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SubscriptionFeedUiState(
    val isLoading: Boolean = false,
    val items: List<SubscriptionFeedItem> = emptyList(),
    /** 当前订阅的标签名（中文） */
    val subscribedTags: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * 订阅内容流（2026-10-23）。
 *
 * 这是订阅闭环的**读回**一环：订阅 → 有内容 → 这里能看到。
 *
 * 之前订阅只有「写」没有「读」：订阅行写进了库，但**没有任何界面**能显示
 * 「你订阅的标签下产生了什么」。用户反馈「订阅功能好像没有功能闭环」——
 * 说的就是这一环。
 */
@HiltViewModel
class SubscriptionFeedViewModel @Inject constructor(
    private val repository: SubscriptionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubscriptionFeedUiState())
    val uiState: StateFlow<SubscriptionFeedUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val resp = repository.feed(limit = 50)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    items = resp.items,
                    // 标签名由 feed 端点一起返回（避免多打一次 tags 接口）
                    subscribedTags = resp.tags,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = friendlyError(e, fallback = "加载订阅内容失败"),
                )
            }
        }
    }
}
