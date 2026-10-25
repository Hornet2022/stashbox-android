package com.tingxia.audio.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 首屏计时器的行为契约（2026-10-25）。
 *
 * ## 为什么要测「只报一次」
 *
 * 冷启动一次 = 一条记录。如果同一个进程里报了两遍，
 * 后台看到的「首屏 P95」就掺了同一台手机的重复样本 ——
 * 指标会被人为拉低（或者在样本量小时被放大），而**指标本身看起来还挺正常**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FirstFrameTrackerTest {

    @Test
    fun `第一次取到首屏耗时`() {
        val tracker = FirstFrameTracker { 10_000L }

        val duration = tracker.consumeFirstFrame()

        assertNotNull("第一帧画出来了必须有耗时", duration)
        assertTrue("耗时不该是负数：$duration", duration!! >= 0L)
    }

    @Test
    fun `同一个进程只报一次`() {
        val tracker = FirstFrameTracker { 10_000L }

        assertNotNull(tracker.consumeFirstFrame())
        assertNull("第二次不该再报", tracker.consumeFirstFrame())
    }

    @Test
    fun `耗时不为负`() {
        // 极端情况：系统时钟被往回调。负数会直接污染 P95。
        val tracker = FirstFrameTracker { 0L }

        assertTrue(tracker.consumeFirstFrame()!! >= 0L)
    }
}
