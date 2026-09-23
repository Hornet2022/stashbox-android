package com.tingxia.audio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 文章实体（对应后端 GET /api/v1/articles / GET /api/v1/articles/{id}）。
 *
 * 后端字段是 snake_case（audio_url / task_id / created_at / duration_sec），
 * kotlinx-serialization 默认不做命名转换 —— 必须用 @SerialName 显式映射，
 * 否则详情页 article.audioUrl 永远为 null，auto-play 永远不触发。
 *
 * @param id        文章唯一 id
 * @param url       原文链接（详情页可复制）
 * @param title     标题
 * @param source    来源标识：wechat / douyin / general
 * @param status    蒸馏状态（见 [DistillStatus]）
 * @param taskId    关联的蒸馏任务 id（status != ready 时用于轮询）
 * @param audioUrl  蒸馏完成后的音频地址（status == ready 时存在）
 * @param createdAt 创建时间（ISO 字符串）
 * @param durationSec 蒸馏音频时长（秒，列表 / 详情页可显示）
 */
@Serializable
data class Article(
    val id: String,
    val url: String = "",
    val title: String? = null,
    val source: String = "general",
    val status: DistillStatus = DistillStatus.PENDING,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("audio_url") val audioUrl: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("duration_sec") val durationSec: Int? = null,
    // CP-TIME：蒸馏完成时间（distilled_articles.updated_at，ready/failed 时有）
    @SerialName("distilled_at") val distilledAt: String? = null,
    // articles 表的 updated_at（用于失败列表的「失败于」时间）
    @SerialName("updated_at") val updatedAt: String? = null,
    // CP-TAG-FILTER：蒸馏 LLM 自动生成的标签（中文名，pending 时为 null）
    @SerialName("tags") val tags: List<String>? = null,
    // CP-DISTILL-TEXT：LLM 听感改写稿正文（hook/body/outro 以空行分段）。
    // 详情页展示"整理后的正文"；列表页为 120 字摘要。蒸馏未完成时为 null。
    @SerialName("script_text") val scriptText: String? = null,
)
