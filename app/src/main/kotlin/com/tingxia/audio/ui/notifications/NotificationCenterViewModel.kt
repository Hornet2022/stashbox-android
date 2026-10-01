package com.tingxia.audio.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.remote.Notification
import com.tingxia.audio.data.repository.NotificationRepository
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationUiState(
    val notifications: List<Notification> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class NotificationCenterViewModel @Inject constructor(
    private val repository: NotificationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationUiState())
    val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    fun load(unreadOnly: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val notifications = repository.list(unreadOnly = unreadOnly)
                _uiState.value = _uiState.value.copy(
                    notifications = notifications,
                    isLoading = false,
                )
                // 总未读数单独拉取
                val all = repository.list(unreadOnly = false)
                _unreadCount.value = all.count { !it.read }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = friendlyError(e, fallback = "加载失败"),
                )
            }
        }
    }

    fun markRead(id: Int) {
        viewModelScope.launch {
            try {
                val ok = repository.markRead(id)
                if (ok) {
                    _uiState.value = _uiState.value.copy(
                        notifications = _uiState.value.notifications.map {
                            if (it.id == id) it.copy(read = true) else it
                        },
                        error = null,
                    )
                    _unreadCount.value = maxOf(0, _unreadCount.value - 1)
                } else {
                    // 服务端明确拒绝：告诉用户，而不是让角标卡住不动
                    _uiState.value = _uiState.value.copy(error = "标记已读失败，请稍后重试")
                }
            } catch (e: Exception) {
                // 2026-10-02: 原来是 `catch (_: Exception) { }` 空吞。
                // 配合网关缺 POST /notifications/{id}/mark-read 这条路由，
                // 这个点**必然** 404 —— 角标永远不减少，用户看到的是
                // 「点多少次都没用，也没任何提示」，像 App 坏了。
                // 根因已修（路由补上），这里也把失败显性化。
                _uiState.value = _uiState.value.copy(
                    error = "标记已读失败：${e.message ?: "网络异常"}"
                )
            }
        }
    }

    fun refresh() {
        val unreadOnly = _uiState.value.notifications.any { !it.read }
        load(unreadOnly = false)
    }
}
