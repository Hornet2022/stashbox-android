package com.tingxia.audio.data.repository

import com.tingxia.audio.data.remote.SubscriptionApi
import com.tingxia.audio.data.remote.SubscriptionFeedResponse
import javax.inject.Inject

/**
 * 订阅内容流仓库。
 *
 * 订阅闭环的「读回」一环：订阅 → 有内容 → 用户能在这里看到。
 */
class SubscriptionRepository @Inject constructor(
    private val api: SubscriptionApi,
) {
    /** 返回整份响应（含 `tags`：当前订阅的标签名），别只取 items。 */
    suspend fun feed(limit: Int = 50, offset: Int = 0): SubscriptionFeedResponse =
        api.feed(limit = limit, offset = offset)
}
