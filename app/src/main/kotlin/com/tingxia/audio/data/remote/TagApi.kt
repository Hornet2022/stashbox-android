package com.tingxia.audio.data.remote

import com.tingxia.audio.data.model.SubscribeResponse
import com.tingxia.audio.data.model.TagsResponse
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface TagApi {
    @GET("api/v1/tags")
    suspend fun listTags(): TagsResponse

    @POST("api/v1/tags/{tag_id_or_slug}/subscribe")
    suspend fun subscribeTag(@Path("tag_id_or_slug") tagIdOrSlug: String): SubscribeResponse

    @POST("api/v1/tags/{tag_id_or_slug}/unsubscribe")
    suspend fun unsubscribeTag(@Path("tag_id_or_slug") tagIdOrSlug: String): SubscribeResponse
}
