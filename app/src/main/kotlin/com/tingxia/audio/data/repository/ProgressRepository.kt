package com.tingxia.audio.data.repository

import com.tingxia.audio.data.remote.ProgressApi
import com.tingxia.audio.data.remote.ProgressUpdateRequest
import javax.inject.Inject

class ProgressRepository @Inject constructor(
    private val api: ProgressApi,
) {
    suspend fun updateProgress(articleId: String, positionSec: Int, totalSec: Int? = null) =
        api.updateProgress(articleId, ProgressUpdateRequest(position_sec = positionSec, total_sec = totalSec))

    suspend fun getProgress(articleId: String) =
        api.getProgress(articleId)
}
