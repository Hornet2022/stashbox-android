package com.tingxia.audio.data.repository

import com.tingxia.audio.data.remote.DeviceInfo
import com.tingxia.audio.data.remote.FeedbackApi
import com.tingxia.audio.data.remote.FeedbackCategory
import com.tingxia.audio.data.remote.FeedbackItem
import com.tingxia.audio.data.remote.FeedbackRequest
import com.tingxia.audio.data.remote.FeedbackResponse
import javax.inject.Inject

class FeedbackRepository @Inject constructor(
    private val api: FeedbackApi,
) {
    suspend fun submit(
        category: FeedbackCategory,
        content: String,
        rating: Int? = null,
        articleId: String? = null,
        contact: String? = null,
        deviceInfo: DeviceInfo? = null,
    ): FeedbackResponse =
        api.submitFeedback(
            FeedbackRequest(
                article_id = articleId,
                category = category.apiValue,
                rating = rating,
                content = content,
                contact = contact,
                device_info = deviceInfo,
            )
        )

    suspend fun list(category: FeedbackCategory? = null): List<FeedbackItem> =
        api.listFeedbacks(category?.apiValue).feedbacks
}
