package com.tingxia.audio.data.sync

import com.tingxia.audio.data.remote.Notification

/**
 * 订阅通知的**去重逻辑**（纯函数，可单测）。
 *
 * ## 为什么要单独抽出来
 *
 * Worker 本身要 Android `Context` 与 `WorkerParameters`，单测里造起来很重
 * （要 Robolectric + work-testing）。但真正容易出错的只是「哪几条该通知、
 * 通知完水位该走到哪」—— 把它抽成纯函数，就能用普通 JUnit 测，
 * 不用把整个 Worker 拖进测试环境。
 *
 * ## 水位（last notified id）的含义
 *
 * 通知 id 是**自增**的 ⇒ 递增。所以只需要记一个数：
 * 「已通知到的最大 id」。比它新的要通知，比它旧的不再通知。
 *
 * ⚠️ **只有真的发出去了才推进水位** —— 若因为「通知权限没授予」而没发出去
 * 却推进了水位，用户后来授权了也**补不回来**（它会被当成「已通知过」跳过）。
 */
object SubscriptionNotifyLogic {

    /**
     * 挑出「该通知的」那些。
     *
     * @param items 通知列表（服务端按 created_at **倒序**返回 ⇒ 新的在前）
     * @param lastNotifiedId 已通知到的最大 id（0 = 还没通知过任何一条）
     */
    fun selectPending(
        items: List<Notification>,
        lastNotifiedId: Int,
    ): List<Notification> {
        val out = mutableListOf<Notification>()
        for (item in items) {
            if (item.id <= lastNotifiedId) break
            out.add(item)
        }
        return out
    }

    /**
     * 通知成功后的新水位。
     *
     * @param notifiedIds 本轮**成功发出**的通知 id
     * @param lastNotifiedId 旧水位
     * @return 新水位（没有成功发出的就保持旧值）
     */
    fun nextWatermark(
        notifiedIds: List<Int>,
        lastNotifiedId: Int,
    ): Int {
        var watermark = lastNotifiedId
        for (id in notifiedIds) {
            if (id > watermark) watermark = id
        }
        return watermark
    }
}
