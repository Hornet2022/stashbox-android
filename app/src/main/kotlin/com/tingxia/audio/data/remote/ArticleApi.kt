package com.tingxia.audio.data.remote

import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.ArticleListResponse
import com.tingxia.audio.data.model.AudioUrlResponse
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.model.DistillStatusResponse
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * 听匣后端 API（api-gateway，dev 端口 8100）。
 *
 * 注意：本期（CP4.3）不接 JWT 鉴权（CP4.6 才接），所有请求无 Authorization header。
 * base url 见 [com.tingxia.audio.di.AppModule]（localhost:8100，emulator 端待改 10.0.2.2）。
 */
interface ArticleApi {

    /** GET /api/v1/articles → 文章列表 */
    @GET("api/v1/articles")
    suspend fun getArticles(): ArticleListResponse

    /** GET /api/v1/articles/{id} → 单篇文章（含 taskId + status） */
    @GET("api/v1/articles/{id}")
    suspend fun getArticle(@Path("id") id: String): Article

    /** GET /api/v1/distill/{task_id} → 蒸馏状态轮询 */
    @GET("api/v1/distill/{task_id}")
    suspend fun getDistillStatus(@Path("task_id") taskId: String): DistillStatusResponse

    /** GET /api/v1/articles/{id}/audio-url → 音频直链 */
    @GET("api/v1/articles/{id}/audio-url")
    suspend fun getAudioUrl(@Path("id") id: String): AudioUrlResponse
}
