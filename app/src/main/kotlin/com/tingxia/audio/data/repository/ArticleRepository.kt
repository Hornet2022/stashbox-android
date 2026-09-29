package com.tingxia.audio.data.repository

import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.AudioUrlResponse
import com.tingxia.audio.data.model.CreateArticleRequest
import com.tingxia.audio.data.model.CreateArticleResponse
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.model.DistillStatusResponse
import com.tingxia.audio.data.model.DistillTriggerResponse
import com.tingxia.audio.data.model.ListenCompleteRequest
import com.tingxia.audio.data.model.ListenCompleteResponse
import com.tingxia.audio.data.model.RatingRequest
import com.tingxia.audio.data.model.RatingResponse
import com.tingxia.audio.data.model.RetryResponse
import com.tingxia.audio.data.model.SkipRequest
import com.tingxia.audio.data.model.SkipResponse
import com.tingxia.audio.data.remote.ArticleApi
import javax.inject.Inject

/**
 * 文章数据仓库：包装 [ArticleApi] 的 Retrofit 调用，向 UI 层屏蔽网络细节。
 *
 * 本期(CP4.3)数据来自真后端，但单测使用 fake 实现（见 test 目录），
 * 不在此处做缓存/DB（本地 DB 留待 CP5）。
 *
 * 任务级操作（variants / evaluation）已迁出至 [EvaluationRepository] /
 * [VariantRepository]，与文章级 CRUD 分离。
 */
class ArticleRepository @Inject constructor(private val api: ArticleApi) {

    suspend fun getArticles(): List<Article> = api.getArticles().articles

    // CP-TAG-FILTER：按 Tag.slug 过滤；slug 为 null 拉全量
    suspend fun getArticlesByTag(tag: String): List<Article> = api.getArticles(tag).articles

    suspend fun getArticle(id: String): Article = api.getArticle(id)

    /**
     * §1.3 文章级状态（新路径，取代旧 `GET /distill/{task_id}`）。
     *
     * 旧 `getDistillStatus(taskId)` 保留向后兼容：内部转调新路径，
     * 但推荐 UI 切到 `getArticleStatus(id)` 拿全字段。
     */
    suspend fun getArticleStatus(articleId: String): DistillStatusResponse =
        api.getArticleStatus(articleId)

    /** 旧路径兼容包装：仅返回 status 字段。**新代码用 [getArticleStatus]。 */
    @Deprecated("新代码走 getArticleStatus(id)")
    suspend fun getDistillStatus(taskId: String): DistillStatus =
        api.getDistillStatusByTaskId(taskId).status

    /** §1.4 主档音频地址（128k，签名 URL）。**过期后重新请求本端点**，不要长缓存。 */
    suspend fun getAudioUrl(id: String): AudioUrlResponse = api.getAudioUrl(id)

    /** 便利：仅取 URL（多数调用点关心字符串） */
    suspend fun getAudioUrlString(id: String): String = api.getAudioUrl(id).audio_url

    // CP5.2-A: 重试失败蒸馏
    suspend fun retryArticle(id: String): RetryResponse = api.retryArticle(id)

    // CP10.5: 创建文章 — 后端自动派蒸馏任务
    // 返回专门的 [CreateArticleResponse](字段全 nullable),不复用 Article,
    // 解决 CP10.4 暴露的 "Field 'id' is required" 反序列化失败。
    suspend fun createArticle(url: String): CreateArticleResponse =
        api.createArticle(CreateArticleRequest(url))

    // CP10.5: 手动触发蒸馏(后端返回任务触发响应,字段全 nullable)
    suspend fun distillArticle(id: String): DistillTriggerResponse = api.distillArticle(id)

    // CP-DELETE: 删除文章(硬删除)。失败抛异常,由调用方(VM)捕获提示。
    // 404 = 文章不存在或不属于当前用户(后端对"越权"统一回 404,不泄露存在性)。
    suspend fun deleteArticle(id: String) = api.deleteArticle(id)

    // ─────────────── §2.1 评分 ───────────────
    suspend fun rateArticle(id: String, rating: Int, comment: String? = null): RatingResponse =
        api.rateArticle(id, RatingRequest(rating = rating, comment = comment))

    // ─────────────── §2.2 听完上报 ───────────────
    suspend fun markListened(id: String, durationSec: Int? = null): ListenCompleteResponse =
        api.markListened(id, ListenCompleteRequest(durationSec = durationSec))

    // ─────────────── §2.3 跳过上报 ───────────────
    suspend fun skipArticle(id: String, reason: String): SkipResponse =
        api.skipArticle(id, SkipRequest(reason = reason))
}

