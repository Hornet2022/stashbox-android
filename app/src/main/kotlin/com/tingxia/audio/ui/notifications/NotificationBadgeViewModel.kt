package com.tingxia.audio.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 首页铃铛的未读角标（2026-10-23）。
 *
 * 状态本身放在**单例仓库**里（NotificationRepository.unreadCount），
 * 这样「在通知页标了已读 → 首页角标立刻减一」能跨屏幕生效；
 * 这个 ViewModel 只负责「进首页时拉一次」。
 */
@HiltViewModel
class NotificationBadgeViewModel @Inject constructor(
    private val repository: NotificationRepository,
) : ViewModel() {

    val unreadCount: StateFlow<Int> = repository.unreadCount

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refreshUnreadCount()
        }
    }
}
