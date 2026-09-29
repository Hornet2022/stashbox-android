package com.tingxia.audio.data

import com.tingxia.audio.data.remote.ProgressApi
import com.tingxia.audio.data.remote.ProgressGetResponse
import com.tingxia.audio.data.remote.ProgressUpdateRequest
import com.tingxia.audio.data.remote.ProgressUpdateResponse

/**
 * 测试用 ProgressApi fake(放在独立文件以便多测试复用)。
 */
class FakeProgressApi(
    val getResponse: ProgressGetResponse = ProgressGetResponse(article_id = "a", position_sec = null, total_sec = null),
    val updateOk: Boolean = true,
) : ProgressApi {
    override suspend fun updateProgress(articleId: String, body: ProgressUpdateRequest): ProgressUpdateResponse =
        ProgressUpdateResponse(ok = updateOk, article_id = articleId, position_sec = body.position_sec)

    override suspend fun getProgress(articleId: String): ProgressGetResponse =
        getResponse.copy(article_id = articleId)
}