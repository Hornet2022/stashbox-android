package com.tingxia.audio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 音频启动计时器的行为契约（2026-10-25）。
 *
 * ## 为什么这个类要单独测
 *
 * 它守着「音频启动 < 500ms」这条指标的全部**语义**：
 * 什么时候算启动、什么时候**不算**（队列自动续播 / 暂停后恢复 / 播放失败）。
 *
 * 这几条分支漏掉一条，后果不是报错，而是**上报出一个假的、巨大的启动耗时** ——
 * 直接把这条指标污染掉，而且外部完全看不出来（数字看起来只是「有点大」）。
 *
 * 所以这里用假时钟做确定性断言，不依赖 ExoPlayer 真的加载出声音。
 */
class AudioStartTimerTest {

    /** 假时钟：测试自己推进。 */
    private var clock = 0L

    private val timer = AudioStartTimer { clock }

    @Test
    fun `点播放到出声的时长就是启动耗时`() {
        clock = 1_000L
        timer.onPlayRequested("art_1")

        clock = 1_250L
        val start = timer.consumeOnPlaying()

        assertNotNull("出声了就必须能取出这次启动", start)
        assertEquals(250L, start!!.durationMs)
        assertEquals("art_1", start.articleId)
    }

    @Test
    fun `没有点过播放就不该上报`() {
        // 队列自动续播 / 耳机「下一首」也会让 isPlaying 变 true，
        // 但那不是「启动」—— 用户没等音频（声音本来就在）。
        assertNull("没人点播放就不该有启动耗时", timer.consumeOnPlaying())
    }

    @Test
    fun `同一次启动只报一次`() {
        timer.onPlayRequested("art_1")

        assertNotNull(timer.consumeOnPlaying())
        assertNull("第二次不能再报（否则一次播放算两条）", timer.consumeOnPlaying())
    }

    @Test
    fun `连点两下只算最后一次`() {
        clock = 1_000L
        timer.onPlayRequested("art_1")

        clock = 1_500L
        timer.onPlayRequested("art_2") // 连点

        clock = 1_700L
        val start = timer.consumeOnPlaying()!!

        assertEquals("时长要从最后一次点击算，不是累加", 200L, start.durationMs)
        assertEquals("art_2", start.articleId)
    }

    @Test
    fun `播放失败后这次启动作废`() {
        timer.onPlayRequested("art_1")
        timer.onPlaybackError()

        assertNull("失败的那次永远不会出声，不能留着等下一次误报", timer.consumeOnPlaying())
    }

    @Test
    fun `暂停后这次启动作废`() {
        timer.onPlayRequested("art_1")
        timer.onPausedOrStopped()

        assertNull("暂停后按播放是恢复不是启动", timer.consumeOnPlaying())
    }

    @Test
    fun `时长不会为负`() {
        clock = 5_000L
        timer.onPlayRequested("art_1")

        clock = 4_000L // 极端情况：时钟倒退
        assertEquals("负时长会污染 P95", 0L, timer.consumeOnPlaying()!!.durationMs)
    }

    @Test
    fun `没有文章 id 时也能报`() {
        // 全屏播放器的 IDLE 恢复路径不传 articleId —— 那是合法的，不能因此不上报。
        timer.onPlayRequested(null)

        val start = timer.consumeOnPlaying()!!
        assertNull(start.articleId)
    }
}
