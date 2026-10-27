package com.tingxia.audio.data

import com.tingxia.audio.data.model.SubscribeResponse
import com.tingxia.audio.data.model.Tag
import com.tingxia.audio.data.model.TagsResponse
import com.tingxia.audio.data.remote.TagApi

/**
 * 测试用 TagApi 替身。
 *
 * ArticleDetailViewModel / ArticleListViewModel 构造需要 TagRepository，
 * TagRepository 依赖 TagApi。本类提供默认实现，测试用例只需覆写关心的方法。
 *
 * 默认行为：
 * - listTags: 返回空列表
 * - subscribeTag / unsubscribeTag: 返回 ok=true
 */
open class FakeTagApi : TagApi {
    var tags: List<Tag> = emptyList()
    var subscribeResponses: MutableMap<String, SubscribeResponse> = mutableMapOf()
    var unsubscribeResponses: MutableMap<String, SubscribeResponse> = mutableMapOf()
    var subscribeError: Throwable? = null
    var unsubscribeError: Throwable? = null

    override suspend fun listTags(): TagsResponse = TagsResponse(tags = tags)

    override suspend fun subscribeTag(tagIdOrSlug: String): SubscribeResponse {
        subscribeError?.let { throw it }
        return subscribeResponses[tagIdOrSlug]
            ?: SubscribeResponse(ok = true)
    }

    override suspend fun unsubscribeTag(tagIdOrSlug: String): SubscribeResponse {
        unsubscribeError?.let { throw it }
        return unsubscribeResponses[tagIdOrSlug]
            ?: SubscribeResponse(ok = true)
    }
}