package com.tingxia.audio.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable
data class ProgressUpdateRequest(
    val position_sec: Int,
    val total_sec: Int? = null,
)

@Serializable
data class ProgressUpdateResponse(
    val ok: Boolean,
    val article_id: String,
    val position_sec: Int,
)

@Serializable
data class ProgressGetResponse(
    val article_id: String,
    val position_sec: Int? = null,
    val total_sec: Int? = null,
)

interface ProgressApi {
    @POST("api/v1/articles/{article_id}/progress")
    suspend fun updateProgress(
        @Path("article_id") articleId: String,
        @Body body: ProgressUpdateRequest,
    ): ProgressUpdateResponse

    @GET("api/v1/articles/{article_id}/progress")
    suspend fun getProgress(
        @Path("article_id") articleId: String,
    ): ProgressGetResponse
}
