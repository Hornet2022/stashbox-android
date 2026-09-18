package com.tingxia.audio.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class Favorite(
    val id: Int,
    val article_id: String,
    val folder: String,
    val note: String?,
    val created_at: String,
)

@Serializable
data class FavoritesResponse(
    val favorites: List<Favorite>,
)

@Serializable
data class FolderCount(
    val folder: String,
    val count: Int,
)

@Serializable
data class FoldersResponse(
    val folders: List<FolderCount>,
)

@Serializable
data class AddFavoriteRequest(
    val folder: String = "default",
    val note: String? = null,
)

@Serializable
data class AddFavoriteResponse(
    val ok: Boolean,
    val id: Int,
    val folder: String,
)

@Serializable
data class UpdateFavoriteRequest(
    val folder: String? = null,
    val note: String? = null,
)

@Serializable
data class UpdateFavoriteResponse(
    val ok: Boolean,
    val id: Int,
)

@Serializable
data class LaterListen(
    val id: Int,
    val article_id: String,
    val snooze_until: String?,
    val created_at: String,
)

@Serializable
data class LaterListensResponse(
    val later_listens: List<LaterListen>,
)

@Serializable
data class SnoozeRequest(
    val snooze_until: String? = null,
)

@Serializable
data class SnoozeResponse(
    val ok: Boolean,
    val id: Int? = null,
)

interface FavoritesApi {
    @GET("api/v1/favorites")
    suspend fun listFavorites(@Query("folder") folder: String? = null): FavoritesResponse

    @GET("api/v1/favorites/folders")
    suspend fun listFolders(): FoldersResponse

    @POST("api/v1/articles/{article_id}/favorites")
    suspend fun addFavorite(
        @Path("article_id") articleId: String,
        @Body body: AddFavoriteRequest,
    ): AddFavoriteResponse

    @PATCH("api/v1/favorites/{favorite_id}")
    suspend fun updateFavorite(
        @Path("favorite_id") favoriteId: Int,
        @Body body: UpdateFavoriteRequest,
    ): UpdateFavoriteResponse

    @DELETE("api/v1/favorites/{favorite_id}")
    suspend fun deleteFavorite(@Path("favorite_id") favoriteId: Int): Map<String, Boolean>

    @GET("api/v1/later-listens")
    suspend fun listLaterListens(): LaterListensResponse

    @POST("api/v1/articles/{article_id}/snooze")
    suspend fun snooze(
        @Path("article_id") articleId: String,
        @Body body: SnoozeRequest,
    ): SnoozeResponse

    @DELETE("api/v1/articles/{article_id}/snooze")
    suspend fun unsnooze(@Path("article_id") articleId: String): Map<String, Boolean>
}
