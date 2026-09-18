package com.tingxia.audio.data.repository

import com.tingxia.audio.data.model.Tag
import com.tingxia.audio.data.remote.TagApi
import javax.inject.Inject

class TagRepository @Inject constructor(
    private val tagApi: TagApi,
) {
    suspend fun listTags(): List<Tag> = tagApi.listTags().tags

    suspend fun subscribe(tagIdOrSlug: String): Boolean =
        tagApi.subscribeTag(tagIdOrSlug).ok

    suspend fun unsubscribe(tagIdOrSlug: String): Boolean =
        tagApi.unsubscribeTag(tagIdOrSlug).ok
}
