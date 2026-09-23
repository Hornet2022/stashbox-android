package com.tingxia.audio.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Tag(
    val id: String,
    val name: String,
    val category: String,
    // P1-1：服务端返回的"已订阅"标记，便于列表展示 + 避免 UI 一直空 subscribedIds。
    // 默认 false 兼容旧服务端 / 测试 fixture（无此字段）。
    val subscribed: Boolean = false,
    // CP-TAG-FILTER：该 user 在此标签下已蒸馏的文章数（订阅页跳文章流用）。
    // 默认 0 兼容旧服务端（无此字段）。
    val article_count: Int = 0,
)

@Serializable
data class TagsResponse(
    val tags: List<Tag> = emptyList(),
)

@Serializable
data class SubscribeResponse(
    val ok: Boolean,
    val already_subscribed: Boolean? = null,
    val was_subscribed: Boolean? = null,
)
