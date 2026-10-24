package com.tingxia.audio.data.sync

import com.tingxia.audio.data.remote.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 订阅通知的**去重逻辑**（水位）。
 *
 * ## 为什么要锁它
 *
 * 水位（last notified id）算错的后果很具体：
 *
 * · 水位推进太快 ⇒ **漏通知**（用户永远收不到某些更新）；
 * · 水位推进太慢 ⇒ **重复通知**（每 15 分钟弹一次同样的内容）。
 *
 * 而它错的场景偏偏是**权限没授予**那种（通知发不出去却推进了水位）——
 * 用户后来授权了，也补不回来（被当成「已通知过」跳过）。
 */
class SubscriptionNotifyLogicTest {

    private fun notif(id: Int, articleId: String = "art_$id") = Notification(
        id = id,
        article_id = articleId,
        tag_slug = "tech",
        title = "新文章：$id",
        body = "你订阅的 科技 标签有新文章蒸馏完成",
        deeplink = "stashbox://detail/$articleId",
        read = false,
        created_at = "2026-10-24T00:00:00",
    )

    @Test
    fun `没通知过时_全部都要通知`() {
        val items = listOf(notif(3), notif(2), notif(1))
        val pending = SubscriptionNotifyLogic.selectPending(items, lastNotifiedId = 0)
        assertEquals(listOf(3, 2, 1), pending.map { it.id })
    }

    @Test
    fun `已通知过的不再通知`() {
        val items = listOf(notif(5), notif(4), notif(3))
        val pending = SubscriptionNotifyLogic.selectPending(items, lastNotifiedId = 3)
        assertEquals(listOf(5, 4), pending.map { it.id })
    }

    @Test
    fun `通知成功后水位推进到最大id`() {
        val w = SubscriptionNotifyLogic.nextWatermark(
            notifiedIds = listOf(5, 4),
            lastNotifiedId = 3,
        )
        assertEquals(5, w)
    }

    @Test
    fun `没成功发出时水位保持不变_这样权限后来授予也能补回来`() {
        // ⚠️ 这条是核心：水位只按**成功发出**的推进。
        // 若没发出去也推进，用户后来授权了也补不回来。
        val w = SubscriptionNotifyLogic.nextWatermark(
            notifiedIds = emptyList(),
            lastNotifiedId = 3,
        )
        assertEquals(3, w)
    }

    @Test
    fun `部分成功时水位只走到成功的那条`() {
        val w = SubscriptionNotifyLogic.nextWatermark(
            notifiedIds = listOf(4),
            lastNotifiedId = 3,
        )
        assertEquals(4, w)
        // 5 没成功 → 水位不能到 5
        assertTrue(SubscriptionNotifyLogic.nextWatermark(listOf(4), 3) < 5)
    }

    @Test
    fun `空列表时没有待通知的`() {
        assertTrue(SubscriptionNotifyLogic.selectPending(emptyList(), 0).isEmpty())
    }
}
