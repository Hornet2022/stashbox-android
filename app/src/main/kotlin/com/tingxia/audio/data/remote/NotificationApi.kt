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
    /**
     * 未读总数（后端已返回，见 user-service 的 GET /api/v1/notifications）。
     *
     * 之前模型里**没声明**这个字段 → 首页铃铛没有红点 → 用户根本不知道
     * 「订阅有更新」这件事存在（订阅闭环的最后一环）。
     *
     * 默认 0：后端老版本没返回时不能崩（kotlinx.serialization 对缺失字段
     * 会用默认值）。
     */
    val unread_count: Int = 0,
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

    /**
     * 回报「这条通知已经到达设备」（2026-10-24）。
     *
     * 没有厂商推送通道时，App 自己拉通知中心 → 发**本地系统通知** →
     * 通知真的出现在手机通知栏 → 回报本端点。
     *
     * 与 [markRead] 是**两件事**：投递 ≠ 用户看过。
     * 合并的话角标会被提前清零 ⇒ 用户还没看到，「有更新」这件事又没人知道。
     */
    @POST("api/v1/notifications/{notification_id}/delivered")
    suspend fun markDelivered(@Path("notification_id") notificationId: Int): MarkReadResponse
}
