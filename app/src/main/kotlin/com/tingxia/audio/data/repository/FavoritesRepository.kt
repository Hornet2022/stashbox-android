package com.tingxia.audio.data.repository

import com.tingxia.audio.data.remote.AddFavoriteRequest
import com.tingxia.audio.data.remote.AddFavoriteResponse
import com.tingxia.audio.data.remote.Favorite
import com.tingxia.audio.data.remote.FavoritesApi
import com.tingxia.audio.data.remote.FolderCount
import com.tingxia.audio.data.remote.LaterListen
import com.tingxia.audio.data.remote.SnoozeRequest
import com.tingxia.audio.data.remote.SnoozeResponse
import com.tingxia.audio.data.remote.UpdateFavoriteRequest
import com.tingxia.audio.data.remote.UpdateFavoriteResponse
import javax.inject.Inject

class FavoritesRepository @Inject constructor(
    private val api: FavoritesApi,
) {
    suspend fun listFavorites(folder: String? = null): List<Favorite> =
        api.listFavorites(folder).favorites

    suspend fun listFolders(): List<FolderCount> = api.listFolders().folders

    suspend fun addFavorite(articleId: String, folder: String = "default", note: String? = null): AddFavoriteResponse =
        api.addFavorite(articleId, AddFavoriteRequest(folder, note))

    suspend fun updateFavorite(id: Int, folder: String? = null, note: String? = null): UpdateFavoriteResponse =
        api.updateFavorite(id, UpdateFavoriteRequest(folder, note))

    suspend fun deleteFavorite(id: Int): Boolean = api.deleteFavorite(id)["ok"] ?: false

    suspend fun listLaterListens(): List<LaterListen> = api.listLaterListens().later_listens

    suspend fun snooze(articleId: String, snoozeUntil: String? = null): SnoozeResponse =
        api.snooze(articleId, SnoozeRequest(snoozeUntil))

    suspend fun unsnooze(articleId: String): Boolean = api.unsnooze(articleId)["ok"] ?: false
}
