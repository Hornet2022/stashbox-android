package com.tingxia.audio.data.remote

import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.ArticleListResponse
import com.tingxia.audio.data.model.AudioUrlResponse
import com.tingxia.audio.data.model.CreateArticleRequest
import com.tingxia.audio.data.model.CreateArticleResponse
import com.tingxia.audio.data.model.DistillStatusResponse
import com.tingxia.audio.data.model.DistillTriggerResponse
import com.tingxia.audio.data.model.ListenCompleteRequest
import com.tingxia.audio.data.model.ListenCompleteResponse
import com.tingxia.audio.data.model.RatingRequest
import com.tingxia.audio.data.model.RatingResponse
import com.tingxia.audio.data.model.RetryResponse
import com.tingxia.audio.data.model.SkipRequest
import com.tingxia.audio.data.model.SkipResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 听匣后端文章级 API（api-gateway，dev 端口 8100）。
 *
 * base url 见 [com.tingxia.audio.di.NetworkModule]（emulator 默认 10.0.2.2:8100）。
 *
 * 任务级接口（variants / evaluation）已拆分到 [DistillationApi]。
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

    /**
     * 旧路径蒸馏状态轮询：`GET /distill/{task_id}`。
     *
     * **Deprecated**：v1.2 §1.3 已迁到 article 级 `GET /articles/{id}/status`（字段更全）。
     * 新代码请用 [getArticleStatus]。本方法仅作向后兼容保留 —— 若响应里 task_id 为空，
     * 是旧服务端未升 alembic 0029 的兜底场景。
     */
    @Deprecated("新代码走 getArticleStatus(id)", ReplaceWith("getArticleStatus(id)"))
    @GET("api/v1/distill/{task_id}")
    suspend fun getDistillStatusByTaskId(@Path("task_id") taskId: String): DistillStatusResponse

    /**
     * §1.3 文章级状态（**新路径**，取代旧 `GET /distill/{task_id}`）。
     *
     * 返回字段更全：status / task_id / task_status / audio_url / audio_duration_sec /
     * tags / quality_score / created_at / updated_at。
     */
    @GET("api/v1/articles/{id}/status")
    suspend fun getArticleStatus(@Path("id") id: String): DistillStatusResponse

    /**
     * §1.4 主档音频地址（128k，签名 URL）。
     *
     * 仅 `status=ready` 可用，否则 404 —— 客户端把 404 当作"还没好"继续轮询 §1.3，
     * 不要当错误弹。
     */
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

    // ─────────────── §2.1 1-5 星评分（双轨之一） ───────────────
    @POST("api/v1/articles/{article_id}/rate")
    suspend fun rateArticle(
        @Path("article_id") id: String,
        @Body body: RatingRequest,
    ): RatingResponse

    // ─────────────── §2.2 听完上报 ───────────────
    @POST("api/v1/articles/{article_id}/listen-complete")
    suspend fun markListened(
        @Path("article_id") id: String,
        @Body body: ListenCompleteRequest,
    ): ListenCompleteResponse

    // ─────────────── §2.3 跳过上报 ───────────────
    @POST("api/v1/articles/{article_id}/skip")
    suspend fun skipArticle(
        @Path("article_id") id: String,
        @Body body: SkipRequest,
    ): SkipResponse
}