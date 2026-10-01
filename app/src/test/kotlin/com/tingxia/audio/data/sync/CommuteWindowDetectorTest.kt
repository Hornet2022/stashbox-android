package com.tingxia.audio.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 通勤时段判断（2026-10-02）。
 *
 * 这个判断错的方向不对称：
 * - 该预加载却没预 → 地铁里播不了（用户可感知）
 * - 不该预却预了 → 白耗流量、挤掉用户已缓存的内容（用户不易察觉但会积累）
 * 所以测试里对"不乱下"的约束比对"该下"更密。
 */
class CommuteWindowDetectorTest {

    /** 造一个"某年某月某日某小时"的时间戳。 */
    private fun ts(hour: Int, dayOfMonth: Int = 15): Long = Calendar.getInstance().apply {
        set(Calendar.YEAR, 2026)
        set(Calendar.MONTH, Calendar.OCTOBER)
        set(Calendar.DAY_OF_MONTH, dayOfMonth)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 30)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `样本不足时不学习 用默认窗口兜底`() {
        assertNull(CommuteWindowDetector.learnWindows(listOf(ts(8), ts(8))))
        assertNull(CommuteWindowDetector.learnWindows(emptyList()))
    }

    @Test
    fun `能学出习惯时段`() {
        // 10 次里 8 次在 8 点 → 峰值 8，窗口取 7..9
        val samples = List(8) { ts(8) } + List(2) { ts(20) }
        val windows = CommuteWindowDetector.learnWindows(samples)
        assertEquals(listOf(7..9), windows)
    }

    @Test
    fun `均匀分布学不出规律`() {
        // 24 小时均匀各一次 → 占比 1/24 < 0.3，"随时都在听"不是通勤规律
        val samples = (0..23).map { ts(it) }
        assertNull(CommuteWindowDetector.learnWindows(samples))
    }

    @Test
    fun `默认窗口覆盖早晚高峰`() {
        assertTrue(CommuteWindowDetector.shouldPrefetchNow(null, nowHour = 8))
        assertTrue(CommuteWindowDetector.shouldPrefetchNow(null, nowHour = 18))
        assertFalse(CommuteWindowDetector.shouldPrefetchNow(null, nowHour = 14))
    }

    @Test
    fun `学到的窗口覆盖默认窗口`() {
        val learned = listOf(21..23) // 夜归型
        assertTrue(CommuteWindowDetector.shouldPrefetchNow(learned, nowHour = 22))
        // 学到夜归规律后，早高峰就不再预加载了
        assertFalse(CommuteWindowDetector.shouldPrefetchNow(learned, nowHour = 8))
    }

    @Test
    fun `学到的窗口边界不越界`() {
        // 峰值在 0 点：窗口收缩成 0..1（而不是 -1..1，也不是平移凑成 0..2）
        val samples = List(10) { ts(0) } + List(2) { ts(13) }
        val windows = CommuteWindowDetector.learnWindows(samples)
        assertEquals(listOf(0..1), windows)
    }

    @Test
    fun `峰值在深夜时窗口也不越界上界`() {
        val samples = List(10) { ts(23) } + List(2) { ts(13) }
        val windows = CommuteWindowDetector.learnWindows(samples)
        assertEquals(listOf(22..23), windows)
    }

    @Test
    fun `空窗口列表等价于没有学到`() {
        // 防御：learnWindows 返回空 list 时不该让 shouldPrefetchNow 崩
        assertTrue(CommuteWindowDetector.shouldPrefetchNow(emptyList(), nowHour = 8))
    }

    @Test
    fun `一次只备一集`() {
        // 100MB LRU 装不下多集；一次下太多会淘汰用户已缓存的
        assertEquals(1, CommuteWindowDetector.prefetchCount())
    }
}
