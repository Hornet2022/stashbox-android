package com.tingxia.audio.data.model

import kotlinx.serialization.Serializable

/**
 * 听匣后端 API 的响应体（与 [com.tingxia.audio.data.remote.ArticleApi] 对应）。
 *
 * 单独放一个文件、不跟 interface 混在一起，避免 K2 + kotlinx-serialization 插件
 * 在同文件内「interface + @Serializable」并存时把 `@Serializable` 解析成内部 typealias 报错。
 */
@Serializable
data class ArticleListResponse(
    val articles: List<Article> = emptyList(),
)

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
