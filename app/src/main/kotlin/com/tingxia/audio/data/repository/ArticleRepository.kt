package com.tingxia.audio.data.repository

import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.CreateArticleRequest
import com.tingxia.audio.data.model.CreateArticleResponse
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.model.DistillTriggerResponse
import com.tingxia.audio.data.model.RetryResponse
import com.tingxia.audio.data.remote.ArticleApi

/**
 * 文章数据仓库:包装 [ArticleApi] 的 Retrofit 调用,向 UI 层屏蔽网络细节。
 *
 * 本期(CP4.3)数据来自真后端,但单测使用 fake 实现(见 test 目录),
 * 不在此处做缓存/DB(本地 DB 留待 CP5)。
 */
class ArticleRepository(private val api: ArticleApi) {

    suspend fun getArticles(): List<Article> = api.getArticles().articles

    suspend fun getArticle(id: String): Article = api.getArticle(id)

    suspend fun getDistillStatus(taskId: String): DistillStatus = api.getDistillStatus(taskId).status

    suspend fun getAudioUrl(id: String): String = api.getAudioUrl(id).audio_url

    // CP5.2-A: 重试失败蒸馏
    suspend fun retryArticle(id: String): RetryResponse = api.retryArticle(id)

    // CP10.5: 创建文章 — 后端自动派蒸馏任务
    // 返回专门的 [CreateArticleResponse](字段全 nullable),不复用 Article,
    // 解决 CP10.4 暴露的 "Field 'id' is required" 反序列化失败。
    suspend fun createArticle(url: String): CreateArticleResponse =
        api.createArticle(CreateArticleRequest(url))

    // CP10.5: 手动触发蒸馏(后端返回任务触发响应,字段全 nullable)
    suspend fun distillArticle(id: String): DistillTriggerResponse = api.distillArticle(id)
}
