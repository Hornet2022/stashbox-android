package com.tingxia.audio.data.remote

import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.ArticleListResponse
import com.tingxia.audio.data.model.AudioUrlResponse
import com.tingxia.audio.data.model.CreateArticleRequest
import com.tingxia.audio.data.model.CreateArticleResponse
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.model.DistillStatusResponse
import com.tingxia.audio.data.model.DistillTriggerResponse
import com.tingxia.audio.data.model.RetryResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 听匣后端 API（api-gateway，dev 端口 8100）。
 *
 * base url 见 [com.tingxia.audio.di.AppModule]（emulator 默认 10.0.2.2:8100）。
 */
interface ArticleApi {

    /** GET /api/v1/articles → 文章列表
 *
 *  CP-TAG-FILTER：tag 为 null 时拉全量；传入 slug 时按 Tag.slug 过滤（仅已蒸馏过且
 *  tags 包含对应中文名的文章）。
 */
    @GET("api/v1/articles")
    suspend fun getArticles(@Query("tag") tag: String? = null): ArticleListResponse

    /** GET /api/v1/articles/{id} → 单篇文章（含 taskId + status） */
    @GET("api/v1/articles/{id}")
    suspend fun getArticle(@Path("id") id: String): Article

    /** GET /api/v1/distill/{task_id} → 蒸馏状态轮询 */
    @GET("api/v1/distill/{task_id}")
    suspend fun getDistillStatus(@Path("task_id") taskId: String): DistillStatusResponse

    /** GET /api/v1/articles/{id}/audio-url → 音频直链 */
    @GET("api/v1/articles/{id}/audio-url")
    suspend fun getAudioUrl(@Path("id") id: String): AudioUrlResponse

    // CP5.2-A: POST /api/v1/articles/{article_id}/retry → 重试失败蒸馏
    @POST("api/v1/articles/{article_id}/retry")
    suspend fun retryArticle(@Path("article_id") id: String): RetryResponse

    // CP10.5: POST /api/v1/articles → 创建文章(后端自动派蒸馏任务)
    // 不再用 Article 当返回类型 — 后端响应字段不全,专门的 [CreateArticleResponse]
    @POST("api/v1/articles")
    suspend fun createArticle(@Body request: CreateArticleRequest): CreateArticleResponse

    // CP10.5: POST /api/v1/articles/{id}/distill → 手动触发蒸馏(后端 add_article 已自动派,这是手动重派)
    // 不复用 [DistillStatusResponse](后者 task_id 是必填),专门的 [DistillTriggerResponse]
    @POST("api/v1/articles/{id}/distill")
    suspend fun distillArticle(@Path("id") id: String): DistillTriggerResponse

    // CP-DELETE: DELETE /api/v1/articles/{id} → 删除文章(硬删除:蒸馏结果+音频级联清理)
    // 后端仅 owner 可删;不存在/非 owner 一律 404。Unit 响应体无 schema 需求,返回 Unit。
    @DELETE("api/v1/articles/{id}")
    suspend fun deleteArticle(@Path("id") id: String)
}
