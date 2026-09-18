package com.tingxia.audio.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.remote.Notification
import com.tingxia.audio.data.repository.NotificationRepository
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
                    error = e.message ?: "加载失败",
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
                    )
                    _unreadCount.value = maxOf(0, _unreadCount.value - 1)
                }
            } catch (_: Exception) {
                // 静默失败，不影响 UI
            }
        }
    }

    fun refresh() {
        val unreadOnly = _uiState.value.notifications.any { !it.read }
        load(unreadOnly = false)
    }
}
