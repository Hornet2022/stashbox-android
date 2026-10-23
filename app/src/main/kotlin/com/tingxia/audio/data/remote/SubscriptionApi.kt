package com.tingxia.audio.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 订阅内容流（2026-10-23）。
 *
 * ## 为什么需要它
 *
 * 订阅此前**只有「写」没有「读回」**：能订阅，但没有任何接口能返回
 * 「我订阅的标签下产生了哪些内容」。于是订阅 = 一个**永远没有回报的开关**。
 *
 * 而 `GET /api/v1/articles?tag=` 只能拿到**自己**的文章（属主过滤），
 * 承载不了「订阅带来的是别人内容」这件事。
 */
@Serializable
data class SubscriptionFeedItem(
    val article_id: String,
    val title: String,
    val source: String? = null,
    val tags: List<String> = emptyList(),
    val audio_url: String? = null,
    val duration_sec: Int? = null,
    val created_at: String? = null,
    /** 与安卓 NavHost 注册的 deep link scheme 一致 */
    val deeplink: String? = null,
)

@Serializable
data class SubscriptionFeedResponse(
    val items: List<SubscriptionFeedItem> = emptyList(),
    val total: Int = 0,
    /** 当前订阅的标签名（中文） */
    val tags: List<String> = emptyList(),
)

interface SubscriptionApi {
    @GET("api/v1/subscriptions/feed")
    suspend fun feed(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): SubscriptionFeedResponse
}
