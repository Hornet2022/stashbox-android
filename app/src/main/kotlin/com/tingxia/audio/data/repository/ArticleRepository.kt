package com.tingxia.audio.data.repository

import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.remote.ArticleApi

/**
 * 文章数据仓库：包装 [ArticleApi] 的 Retrofit 调用，向 UI 层屏蔽网络细节。
 *
 * 本期（CP4.3）数据来自真后端，但单测使用 fake 实现（见 test 目录），
 * 不在此处做缓存/DB（本地 DB 留待 CP5）。
 */
class ArticleRepository(private val api: ArticleApi) {

    suspend fun getArticles(): List<Article> = api.getArticles().articles

    suspend fun getArticle(id: String): Article = api.getArticle(id)

    suspend fun getDistillStatus(taskId: String): DistillStatus = api.getDistillStatus(taskId).status

    suspend fun getAudioUrl(id: String): String = api.getAudioUrl(id).audio_url
}
