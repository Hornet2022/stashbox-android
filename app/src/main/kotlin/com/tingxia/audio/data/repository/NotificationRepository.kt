package com.tingxia.audio.data.repository

import com.tingxia.audio.data.remote.Notification
import com.tingxia.audio.data.remote.NotificationApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

class NotificationRepository @Inject constructor(
    private val api: NotificationApi,
) {
    suspend fun list(unreadOnly: Boolean = false): List<Notification> =
        api.listNotifications(unreadOnly = unreadOnly).notifications

    suspend fun markRead(id: Int): Boolean = api.markRead(id).ok

    /**
     * 未读总数（首页铃铛角标用）。
     *
     * ⚠️ 放在**单例仓库**里而不是某个 ViewModel 里：角标要出现在首页，
     * 而「已读」是在通知页操作的 —— 两者是不同屏幕、不同 ViewModel 作用域，
     * 状态放仓库里才能「在通知页标了已读 → 首页角标立刻减一」。
     *
     * 之前首页铃铛**完全没有角标**（后端 `unread_count` 早就返回了，
     * 安卓的 `NotificationsResponse` 却没声明这个字段）→ 用户不知道
     * 「订阅有更新」，这是订阅闭环断在最后一环的原因之一。
     */
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    /** 拉一次未读数（首页进入时、以及通知页标记已读后调用）。 */
    suspend fun refreshUnreadCount() {
        runCatching { api.listNotifications(unreadOnly = false) }
            .onSuccess { _unreadCount.value = it.unread_count }
    }

    /** 本地先减一（标已读是乐观更新，角标不能等网络回来才动）。 */
    fun decrementUnread() {
        if (_unreadCount.value > 0) _unreadCount.value = _unreadCount.value - 1
    }
}
