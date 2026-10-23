package com.tingxia.audio.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「继续播放」状态的存取（2026-10-23 新增）。
 *
 * Android 13+ 的系统媒体面板会记住上次在听什么并给一个「继续播放」按钮；
 * 按了之后系统向媒体服务发恢复请求（`onPlaybackResumption`）。
 * 不存这个状态，那个按钮就是死的。
 *
 * 这里锁三件事：存取往返、mediaId 承载 articleId、过期不恢复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaybackResumptionTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PlaybackResumption.clear(context)
    }

    @Test
    fun `没有记录时读不到`() {
        assertNull(PlaybackResumption.read(context))
    }

    @Test
    fun `存取往返`() {
        PlaybackResumption.save(context, "art_x", "https://example.com/a.mp3", "标题 A")
        PlaybackResumption.savePosition(context, 42_000L)

        val r = PlaybackResumption.read(context)
        assertNotNull(r)
        assertEquals("art_x", r!!.articleId)
        assertEquals("https://example.com/a.mp3", r.audioUrl)
        assertEquals("标题 A", r.title)
        assertEquals(42_000L, r.positionMs)
    }

    @Test
    fun `mediaId 承载 articleId`() {
        PlaybackResumption.save(context, "art_y", "https://example.com/b.mp3", "标题 B")
        val item = PlaybackResumption.read(context)!!.toMediaItem()
        // 恢复后「当前是哪一篇」要能从播放器直接读出来
        assertEquals("art_y", item.mediaId)
    }

    @Test
    fun `空地址不记录`() {
        PlaybackResumption.save(context, "art_z", "", "空地址")
        assertNull("没有音频地址就没什么可恢复的", PlaybackResumption.read(context))
    }

    @Test
    fun `清除后读不到`() {
        PlaybackResumption.save(context, "art_w", "https://example.com/c.mp3", "标题 C")
        assertNotNull(PlaybackResumption.read(context))
        PlaybackResumption.clear(context)
        assertNull(PlaybackResumption.read(context))
    }
}
