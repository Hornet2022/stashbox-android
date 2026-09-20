package com.tingxia.audio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 听匣后端 API 的响应体(与 [com.tingxia.audio.data.remote.ArticleApi] 对应)。
 *
 * 单独放一个文件、不跟 interface 混在一起,避免 K2 + kotlinx-serialization 插件
 * 在同文件内「interface + @Serializable」并存时把 `@Serializable` 解析成内部 typealias 报错。
 */
@Serializable
data class ArticleListResponse(
    // CP10 fix: backend GET /api/v1/articles returns {"items":[...], "total":N}
    // 之前期待 "articles" → 反序列化失败 → UI "暂无文章" 假象
    val items: List<Article> = emptyList(),
    val total: Int = 0,
) {
    val articles: List<Article> get() = items
}

@Serializable
data class DistillStatusResponse(
    val task_id: String,
    val status: DistillStatus,
    val audio_url: String? = null,
)

@Serializable
data class AudioUrlResponse(
    val audio_url: String,
    val expires_at: String? = null,
)

// CP5.2-A: POST /api/v1/articles/{article_id}/retry 响应体
@Serializable
data class RetryResponse(
    val article_id: String,
    val status: String,
    val retry_count: Int,
    val queued_at: String,
    val distill_triggered: Boolean,
)

// CP10.4: POST /api/v1/articles 请求体 — 只接 url(后端 add_article 自动派蒸馏)
@Serializable
data class CreateArticleRequest(
    @SerialName("url")
    val url: String,
)

// CP10.5: POST /api/v1/articles 响应体 — **不复用 Article**
//
// 原因:CP10.4 真验发现后端 add_article 返的 157-byte JSON body 缺 `id` 字段,
// 直接反序列化到 [Article] 报 "Field 'id' is required",导致 add/capture 业务跑不通。
//
// 不能把 [Article.id] 改 nullable(会污染所有 GET 调用 — §⑰ 红线),
// 所以这里专门为 create 接口写一个**字段全 nullable** 的响应壳,
// 后端任一字段缺失都不会再炸。
@Serializable
data class CreateArticleResponse(
    @SerialName("id") val id: String? = null,
    @SerialName("article_id") val articleId: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("task_id") val taskId: String? = null,
)

// CP10.5: POST /api/v1/articles/{id}/distill 响应体
//
// 同 [CreateArticleResponse] 思路:不依赖后端字段固定存在,
// 全部 nullable + 单独类型,**不**复用 [DistillStatusResponse](后者带必填 task_id)。
@Serializable
data class DistillTriggerResponse(
    @SerialName("status") val status: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("article_id") val articleId: String? = null,
)
