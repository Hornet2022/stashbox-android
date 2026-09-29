package com.tingxia.audio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 听匣后端 API 的响应体（与 [com.tingxia.audio.data.remote.ArticleApi] 对应）。
 *
 * 单独放一个文件、不跟 interface 混在一起，避免 K2 + kotlinx-serialization 插件
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

/**
 * §1.3 文章级状态响应（GET /api/v1/articles/{article_id}/status）。
 *
 * - 这是**新**的状态轮询端点（article 级），比旧 `GET /distill/{task_id}` 字段更全：
 *   `task_id`、`task_status`（内部 7 态原文）、`error`、`audio_url`、`audio_duration_sec`、
 *   `tags`、`quality_score`、`created_at`、`updated_at` 一次拿完。
 * - 轮询节奏建议：pending/distilling 阶段 3s 一次；ready/failed 停止轮询。
 * - 客户端强校验 [DistillStatus] 枚举值；未知值后端已降级为 DISTILLING（容错）。
 */
@Serializable
data class DistillStatusResponse(
    /** `article_id` 仅新路径 §1.3 返回；旧 `GET /distill/{task_id}` 无此字段。 */
    @SerialName("article_id") val articleId: String? = null,
    val status: DistillStatus,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("task_status") val taskStatus: String? = null,
    val error: String? = null,
    @SerialName("audio_url") val audioUrl: String? = null,
    @SerialName("audio_duration_sec") val audioDurationSec: Int? = null,
    val tags: List<String>? = null,
    @SerialName("quality_score") val qualityScore: Double? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/**
 * §1.4 主档音频地址（128k，签名 URL）。
 *
 * - 仅 `status=ready` 可用，否则 404（404 语义 =「还没好」，客户端继续轮询 §1.3，不要当错误弹）
 * - `expires_at` 语义：URL 带签名参数（当前 mock，CP1.8+ 真 OSS 签名），
 *   **过期后重新请求本端点取新 URL，不要长缓存 URL 本身**
 * - `durationSec` 用于详情页时长展示 / 进度条总长 / 听感评分时机（≥ 90% 触发 §2.2）
 */
@Serializable
data class AudioUrlResponse(
    val audio_url: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("duration_sec") val durationSec: Int? = null,
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

// ─────────────── §2.1 评分（1-5 星 + 可选评论） ───────────────
//
// 双轨之一：写 feedback 表，保留旧版入口，与 §2.6 评分并存。
// 校验：rating ∈ [1,5]；comment 可选；不幂等（每次写一条，UI 防连点）。

@Serializable
data class RatingRequest(
    val rating: Int,
    val comment: String? = null,
)

@Serializable
data class RatingResponse(
    val id: String,
    val rating: Int,
    @SerialName("feedback_id") val feedbackId: Int,
)

// ─────────────── §2.2 听完上报 ───────────────
//
// 触发条件建议：播放进度 ≥ 90% 或用户手动划走时。ExoPlayer PLAYBACK_COMPLETE 事件直连。
// ⚠️ 该事件同时是 admin A/B 报表「完听率」的分母来源之一（audio_complete 埋点），如实上报。

@Serializable
data class ListenCompleteRequest(
    @SerialName("duration_sec") val durationSec: Int? = null,
)

@Serializable
data class ListenCompleteResponse(
    val id: String,
    @SerialName("listened_at") val listenedAt: String,
    @SerialName("feedback_id") val feedbackId: Int,
)

// ─────────────── §2.3 跳过（含原因） ───────────────
//
// 推荐枚举（后端用于分类统计，不强制）：too_long / too_short / boring /
// low_quality / not_interested / other。CP8.6 起放宽为自由文本 ≤64 字符。

@Serializable
data class SkipRequest(
    val reason: String,
)

@Serializable
data class SkipResponse(
    val id: String,
    val skip: Boolean,
    @SerialName("feedback_id") val feedbackId: Int,
    val reason: String,
)