package com.tingxia.audio.data.model

import kotlinx.serialization.Serializable

/**
 * 标签分组标题的本地化映射（**旧服务端兜底**）。
 *
 * 服务端 tags.category 是内部取值（生产 seed 恒为 "subject"），没有枚举约束，
 * 直接渲染会露出英文毛刺（真机反馈：订阅页分组标题显示 "subject"）。
 * 新服务端会在 list_tags 里多吐一个 `category_label`（可读中文名，单源定义在后端），
 * 客户端优先用 `category_label`；`KNOWN_TAG_CATEGORIES` 只在旧服务端没有该字段时兜底。
 * 与 FeedbackCategory 同款写法：已知值映射成中文，未知值 fallback 回原文，
 * 保证运营未来新增分类也不会崩、不会露英文。
 */
val KNOWN_TAG_CATEGORIES: Map<String, String> = mapOf(
    "subject" to "主题",
)

@Serializable
data class Tag(
    val id: String,
    val name: String,
    val category: String,
    // P1-1：服务端返回的"已订阅"标记，便于列表展示 + 避免 UI 一直空 subscribedIds。
    // 默认 false 兼容旧服务端 / 测试 fixture（无此字段）。
    val subscribed: Boolean = false,
    // CP-TAG-FILTER：该 user 在此标签下已蒸馏的文章数（用于订阅页跳文章流用）。
    // 默认 0 兼容旧服务端（无此字段）。
    val article_count: Int = 0,
    // 2026-10-26：订阅这个标签后能看到多少篇 —— 全站 da.tags ∪ shared_tags 命中、
    // 且已就绪（有音频、未删除）的文章数。与订阅 feed 的 total 语义一致。
    // 默认 0 兼容旧服务端（无此字段）。
    val subscribable_count: Int = 0,
    // CP-TAG-CATEGORY：服务端 list_tags 返回的可读分组名（单源定义在后端）。
    // 默认 null 兼容旧服务端（无此字段）→ UI 用 KNOWN_TAG_CATEGORIES 兜底。
    val category_label: String? = null,
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
