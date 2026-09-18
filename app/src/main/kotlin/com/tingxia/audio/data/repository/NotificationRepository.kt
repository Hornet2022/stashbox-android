package com.tingxia.audio.data.repository

import com.tingxia.audio.data.remote.Notification
import com.tingxia.audio.data.remote.NotificationApi
import javax.inject.Inject

class NotificationRepository @Inject constructor(
    private val api: NotificationApi,
) {
    suspend fun list(unreadOnly: Boolean = false): List<Notification> =
        api.listNotifications(unreadOnly = unreadOnly).notifications

    suspend fun markRead(id: Int): Boolean = api.markRead(id).ok
}
