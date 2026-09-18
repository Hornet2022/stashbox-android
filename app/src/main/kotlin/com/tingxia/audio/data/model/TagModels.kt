package com.tingxia.audio.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Tag(
    val id: String,
    val name: String,
    val category: String,
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
