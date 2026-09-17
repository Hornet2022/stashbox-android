package com.tingxia.audio.data.model

import kotlinx.serialization.Serializable

/**
 * 文章实体（对应后端 GET /api/v1/articles / GET /api/v1/articles/{id}）。
 *
 * @param id        文章唯一 id
 * @param url       原文链接（详情页可复制）
 * @param title     标题
 * @param source    来源标识：wechat / douyin / general
 * @param status    蒸馏状态（见 [DistillStatus]）
 * @param taskId    关联的蒸馏任务 id（status != ready 时用于轮询）
 * @param audioUrl  蒸馏完成后的音频地址（status == ready 时存在）
 * @param createdAt 创建时间（ISO 字符串）
 */
@Serializable
data class Article(
    val id: String,
    val url: String = "",
    val title: String,
    val source: String = "general",
    val status: DistillStatus = DistillStatus.PENDING,
    val taskId: String? = null,
    val audioUrl: String? = null,
    val createdAt: String = "",
)
