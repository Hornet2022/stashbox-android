package com.tingxia.audio.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

enum class FeedbackCategory(val displayName: String, val apiValue: String) {
    BUG("App 出错了", "bug"),
    FEATURE("我想要的功能", "feature"),
    CONTENT("内容有问题", "content"),
    AUDIO_QUALITY("音频质量", "audio_quality"),
    OTHER("其他", "other"),
}

@Serializable
data class DeviceInfo(
    val app_version: String,
    val os: String,
    val device_model: String,
)

@Serializable
data class FeedbackRequest(
    val article_id: String? = null,
    val category: String,
    val rating: Int? = null,
    val content: String,
    val contact: String? = null,
    val device_info: DeviceInfo? = null,
)

@Serializable
data class FeedbackResponse(
    val ok: Boolean,
    val id: Int,
    val category: String,
)

@Serializable
data class FeedbackItem(
    val id: Int,
    val article_id: String?,
    val category: String,
    val rating: Int?,
    val content: String,
    val created_at: String,
)

@Serializable
data class FeedbackListResponse(
    val feedbacks: List<FeedbackItem>,
)

interface FeedbackApi {
    @POST("api/v1/feedback-v2")
    suspend fun submitFeedback(@Body body: FeedbackRequest): FeedbackResponse

    @GET("api/v1/feedback-v2")
    suspend fun listFeedbacks(
        @Query("category") category: String? = null,
        @Query("limit") limit: Int = 50,
    ): FeedbackListResponse
}
