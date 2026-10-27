package com.tingxia.audio.audio

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 滚动字幕区的数据源（2026-10-27 新增）。
 *
 * ## 为什么要有这条回归
 * 滚动字幕区要同时拿到三样东西：**整条序列**、**当前句下标**、**当前句文本**。
 * 三者如果各算各的，就会出现「高亮的是第 2 句、滚动到第 3 句」这种
 * 看起来像坏了的界面 —— 而且它只在**播到中间**时才出现，静态检查看不出来。
 *
 * 所以这里钉的是**同源性**：三者必须来自同一次位置查询
 * （`SubtitleTimeline.indexAt(position)`），于是
 * 「听到哪」和「看到哪」不可能对不上。
 *
 * ## 断言为什么盯「下标」而不是文本
 * 稿子里出现**重复句子**时，用文本判断「现在是哪一句」会永远命中第一个 ——
 * 文本断言会误判成「通过」。下标没有这个歧义。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerControllerSubtitleCuesTest {

    private lateinit var realPlayer: ExoPlayer
    private lateinit var fakePlayer: CuePositionPlayer
    private lateinit var controller: PlayerController

    @Before
    fun setup() {
        realPlayer = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        fakePlayer = CuePositionPlayer(realPlayer)
        controller = PlayerController(fakePlayer, RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        realPlayer.release()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    private fun idleMain(durationMs: Long) {
        mainLooper.idleFor(Duration.ofMillis(durationMs))
    }

    private fun play(articleId: String = "art_1") = controller.play(
        audioUrl = "https://example.com/audio.mp3",
        title = "t",
        articleId = articleId,
    )

    /** 全屏播放器可见 ⇒ 位置/字幕以 500ms 刷新（和真机一致）。 */
    private fun observe() = controller.startPositionUpdates()

    private fun setScript(id: String, script: String) = controller.setSubtitleScript(id, script)

    @Test
    fun `整条字幕序列会随稿子发布出来`() {
        fakePlayer.durationMs = 30_000L
        setScript("art_1", "第一句话。第二句话。第三句话。")
        play()
        observe()
        idleMain(1_000L)

        println("DEBUG duration=${controller.duration.value} pos=${controller.position.value} script=${controller.currentSubtitleScript} cues=${controller.subtitleCues.value.size} pump=${controller.isPositionPumpRunning}")
        assertEquals(
            "3 句话应该出 3 条字幕",
            3,
            controller.subtitleCues.value.size,
        )
        assertEquals(
            "序列里的文本要和稿子一致",
            "第一句话。",
            controller.subtitleCues.value.first().text,
        )
    }

    @Test
    fun `当前句下标跟着播放位置走且与文本同源`() {
        fakePlayer.durationMs = 30_000L
        setScript("art_1", "第一句话。第二句话。第三句话。")
        play()
        observe()
        idleMain(1_000L)

        fakePlayer.positionMs = 1_000L
        idleMain(600L)
        val firstIndex = controller.currentSubtitleIndex.value
        assertEquals("第一句时下标应为 0", 0, firstIndex)
        assertEquals(
            "下标与当前句文本必须指向同一条",
            controller.subtitleCues.value[firstIndex].text,
            controller.currentSubtitle.value,
        )

        // 15 秒 → 第二句（10~20 秒）
        fakePlayer.positionMs = 15_000L
        idleMain(600L)
        val secondIndex = controller.currentSubtitleIndex.value
        assertEquals("第二句时下标应为 1", 1, secondIndex)
        assertEquals(
            "下标与当前句文本必须指向同一条",
            controller.subtitleCues.value[secondIndex].text,
            controller.currentSubtitle.value,
        )
    }

    @Test
    fun `播完之后下标回到没有`() {
        fakePlayer.durationMs = 30_000L
        setScript("art_1", "第一句话。第二句话。第三句话。")
        play()
        observe()
        idleMain(1_000L)

        fakePlayer.positionMs = 29_000L
        idleMain(600L)
        assertTrue("播的过程中应该有当前句", controller.currentSubtitleIndex.value >= 0)

        fakePlayer.positionMs = 30_500L
        idleMain(600L)
        assertEquals("播完之后不该还指着某一句", -1, controller.currentSubtitleIndex.value)
        assertEquals(null, controller.currentSubtitle.value)
    }

    @Test
    fun `换到没有稿子的文章时序列必须清空`() {
        fakePlayer.durationMs = 30_000L
        setScript("art_1", "第一句话。第二句话。第三句话。")
        play()
        observe()
        idleMain(1_000L)
        assertTrue("先确认序列出来了", controller.subtitleCues.value.isNotEmpty())

        // 第二篇没有稿子 → 序列不能残留第一篇的句子
        // （否则滚动区会一直显示上一篇的内容）
        controller.play(
            audioUrl = "https://example.com/audio2.mp3",
            title = "t2",
            articleId = "art_2",
        )
        idleMain(1_000L)

        assertTrue(
            "换到没有稿子的文章时，序列必须清空",
            controller.subtitleCues.value.isEmpty(),
        )
        assertEquals(-1, controller.currentSubtitleIndex.value)
    }
}

/** 只接管「当前位置」和「总时长」两个 getter，其余全部委托给真实 ExoPlayer。 */
private class CuePositionPlayer(private val delegate: ExoPlayer) : ExoPlayer by delegate {
    var positionMs: Long = 0L
    var durationMs: Long = 0L

    override fun getCurrentPosition(): Long = positionMs

    override fun getDuration(): Long =
        if (durationMs <= 0L) androidx.media3.common.C.TIME_UNSET else durationMs
}
