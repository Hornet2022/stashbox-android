package com.tingxia.audio.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class Notification(
    val id: Int,
    val article_id: String?,
    val tag_slug: String?,
    val title: String,
    val body: String,
    val deeplink: String?,
    val read: Boolean,
    val created_at: String,
)

@Serializable
data class NotificationsResponse(
    val notifications: List<Notification>,
)

@Serializable
data class MarkReadResponse(
    val ok: Boolean,
)

interface NotificationApi {
    @GET("api/v1/notifications")
    suspend fun listNotifications(
        @Query("unread_only") unreadOnly: Boolean = false,
        @Query("limit") limit: Int = 50,
    ): NotificationsResponse

    @POST("api/v1/notifications/{notification_id}/mark-read")
    suspend fun markRead(@Path("notification_id") notificationId: Int): MarkReadResponse
}
