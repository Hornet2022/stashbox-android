package com.tingxia.audio.data

import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.ArticleListResponse
import com.tingxia.audio.data.model.AudioUrlResponse
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

/**
 * 测试用 ArticleApi 替身。
 *
 * CP3.7.0 重构后,ArticleApi 接口扩展了 5 个方法(§1.3 status / §2.1 rate /
 * §2.2 listen-complete / §2.3 skip),任何 anonymous 实现 ArticleApi 的测试都
 * 需要补全。本类提供完整默认实现,测试用例只需覆写关心的方法。
 *
 * 默认行为：
 * - getArticles / getArticle: 由调用方指定
 * - getDistillStatus (legacy): 返 READY
 * - getArticleStatus (§1.3): 返 READY + 占位
 * - getAudioUrl: 由调用方指定
 * - createArticle / distillArticle / retryArticle / rateArticle / markListened / skipArticle: 返空响应
 * - deleteArticle: no-op
 */
open class FakeArticleApi : ArticleApi {
    var articles: List<Article> = emptyList()
    var audioUrls: Map<String, String> = emptyMap()
    var statuses: Map<String, DistillStatus> = emptyMap()
    var statusResponses: Map<String, DistillStatusResponse> = emptyMap()

    override suspend fun getArticles(tag: String?): ArticleListResponse =
        ArticleListResponse(items = articles)

    override suspend fun getArticle(id: String): Article =
        articles.first { it.id == id }

    override suspend fun getDistillStatusByTaskId(taskId: String): DistillStatusResponse =
        DistillStatusResponse(
            articleId = null,
            status = statuses[taskId] ?: DistillStatus.READY,
            taskId = taskId,
        )

    override suspend fun getArticleStatus(id: String): DistillStatusResponse =
        statusResponses[id]
            ?: DistillStatusResponse(
                articleId = id,
                status = statuses[id] ?: DistillStatus.READY,
                taskId = "dst_${id}",
            )

    override suspend fun getAudioUrl(id: String): AudioUrlResponse =
        AudioUrlResponse(audio_url = audioUrls[id] ?: "https://example.com/$id.mp3")

    override suspend fun retryArticle(id: String): RetryResponse =
        RetryResponse(article_id = id, status = "pending", retry_count = 0, queued_at = "", distill_triggered = false)

    override suspend fun createArticle(request: com.tingxia.audio.data.model.CreateArticleRequest): CreateArticleResponse =
        CreateArticleResponse(id = "art_new", status = "pending")

    override suspend fun distillArticle(id: String): DistillTriggerResponse =
        DistillTriggerResponse(status = "pending", taskId = "dst_new", articleId = id)

    override suspend fun deleteArticle(id: String) = Unit

    override suspend fun rateArticle(id: String, body: RatingRequest): RatingResponse =
        RatingResponse(id = id, rating = body.rating, feedbackId = 1)

    override suspend fun markListened(id: String, body: ListenCompleteRequest): ListenCompleteResponse =
        ListenCompleteResponse(id = id, listenedAt = "2026-09-24", feedbackId = 2)

    override suspend fun skipArticle(id: String, body: SkipRequest): SkipResponse =
        SkipResponse(id = id, skip = true, feedbackId = 3, reason = body.reason)
}