package com.tingxia.audio.audio

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.tingxia.audio.data.analytics.AnalyticsSink
import com.tingxia.audio.data.analytics.EventNames
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 播放器 → 埋点的接线（2026-10-25）。
 *
 * ## 为什么单独一个测试文件
 *
 * [AudioStartTimerTest] 测的是**语义**（什么时候算启动）。
 * 这里测的是**接线**：点了播放之后，播放器到底有没有真的把
 * `audio_play_start` 报出去、带没带 `duration_ms` 与 `article_id`。
 *
 * 接线断了（事件名写错、字段漏了、根本没调）单测是测不出来的 ——
 * 而这类错误的表征是「指标一直是空的」，很容易被当成「用户还没触发过」。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerControllerAudioStartTest {

    /** 记录所有上报的假 sink。 */
    private class RecordingSink : AnalyticsSink {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun report(eventName: String, properties: Map<String, Any?>) {
            events += eventName to properties
        }
    }

    private lateinit var player: ExoPlayer
    private lateinit var sink: RecordingSink
    private lateinit var controller: PlayerController

    /** 假时钟。 */
    private var clock = 0L

    @Before
    fun setup() {
        player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        sink = RecordingSink()
        controller = PlayerController(
            player,
            RuntimeEnvironment.getApplication(),
            sink,
            AudioStartTimer { clock },
        )
    }

    @After
    fun tearDown() {
        player.release()
        // 排干主线程：位置轮询泵会一直 postDelayed，不 idle 的话
        // Robolectric 会在用例尾部抛 UnExecutedRunnablesException。
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun play(articleId: String? = "art_1") =
        controller.play(
            audioUrl = "https://example.com/audio.mp3",
            title = "t",
            articleId = articleId,
        )

    @Test
    fun `点播放后出声会上报 audio_play_start 并带上耗时`() {
        clock = 1_000L
        play(articleId = "art_1")

        clock = 1_320L
        controller.handlePlayingStarted()

        assertEquals("必须上报且只报一条", 1, sink.events.size)
        val (name, props) = sink.events[0]
        assertEquals(EventNames.AUDIO_PLAY_START_CLIENT, name)
        assertEquals("耗时必须是毫秒数", 320L, props["duration_ms"])
        assertEquals("art_1", props["article_id"])
    }

    @Test
    fun `没有点播放就不上报`() {
        // 队列自动续播也会走 handlePlayingStarted —— 那不是「启动」。
        controller.handlePlayingStarted()

        assertTrue("没人点播放就不该上报", sink.events.isEmpty())
    }

    @Test
    fun `播放器的 isPlaying 回调会真的触发上报`() {
        // 这一条守的是**接线**：监听器 → handlePlayingStarted → analytics。
        // 真机上让 ExoPlayer 真加载出声音才能走完这条路，
        // 所以这里直接触发监听器回调（Robolectric 里 ExoPlayer 不会真的出声）。
        clock = 1_000L
        play(articleId = "art_1")

        clock = 1_400L
        controller.playerListener.onIsPlayingChanged(true)

        assertEquals("监听器必须把启动埋点接上", 1, sink.events.size)
        assertEquals(EventNames.AUDIO_PLAY_START_CLIENT, sink.events[0].first)
        assertEquals(400L, sink.events[0].second["duration_ms"])
    }

    @Test
    fun `同一次播放只上报一条`() {
        play()
        controller.handlePlayingStarted()
        controller.handlePlayingStarted()

        assertEquals("一次播放只能有一条记录（否则指标被重复计数）", 1, sink.events.size)
    }

    @Test
    fun `暂停后按播放不再上报`() {
        play()
        controller.pause()

        controller.handlePlayingStarted()

        assertTrue("暂停后按播放是恢复不是启动", sink.events.isEmpty())
    }

    @Test
    fun `没有文章 id 时不上报 article_id 字段`() {
        play(articleId = null)

        controller.handlePlayingStarted()

        assertEquals(1, sink.events.size)
        assertFalse(
            "没有 id 就别塞空值（后台按 article_id 聚合时 null 是噪声）",
            sink.events[0].second.containsKey("article_id"),
        )
    }
}
