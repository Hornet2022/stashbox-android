package com.tingxia.audio.audio

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketException

/**
 * 播放中断的自动重连（2026-10-26）。
 *
 * ## 这组测试守的是什么
 *
 * 内测反馈「放后台播放，几分钟后就没声了」。查下来的机制是：
 * 一次瞬时断流 ⇒ ExoPlayer 报错 ⇒ 此前 App **没有任何自动恢复**，
 * 唯一的恢复入口是用户手动点「重试」—— 而后台没人点。
 *
 * ⇒ 修法是「网络类错误自动重连（保位置），内容类错误照旧报错」。
 * 这里守的就是**这个分岔的判定**，以及几个容易写错的边：
 *
 * · 网络类错误**不能**再进 ERROR（那正是原 bug）；
 * · 内容类错误（404 / 解码失败）**不能**自动重连（重试一万次也没用，
 *   而且会让一个已经消失的音频一直耗电重试）；
 * · 重连必须有次数上限；
 * · 手动重试必须**保留位置**（用户听了 3 分钟，点重试不该从头再来）。
 *
 * ## 测不到的部分（要说清楚）
 *
 * 「重连后真的从断点续上了」这件事**单测测不了**：
 * 它需要一条真实的 HTTP 连接在中途断掉。
 * media3 的 `prepareInternal` 是 `resetPosition=false, releaseMediaSourceList=false`
 * （1.4.1 源码），位置与媒体源都保留 —— 这部分靠**真机断网**验证，
 * 见仓内 `docs/后台播放中断_诊断与修复_2026-10-26.md` 的验收清单。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerControllerRecoveryTest {

    private lateinit var player: ExoPlayer
    private lateinit var controller: PlayerController

    @Before
    fun setup() {
        player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        controller = PlayerController(player, RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        player.release()
        // 排干主线程：位置轮询泵与重连定时器都 postDelayed，
        // 不 idle 的话 Robolectric 会在用例尾部抛 UnExecutedRunnablesException。
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    /** 播起来（让播放器里有媒体项 —— 没有媒体项就没得「重连」）。 */
    private fun play() {
        controller.play(
            audioUrl = "https://example.com/audio.mp3",
            title = "t",
            articleId = "art_1",
        )
    }

    /** 一次「连接被重置」类的瞬时网络错误。 */
    private fun transientNetworkError(): PlaybackException = PlaybackException(
        "连接被重置",
        SocketException("Connection reset"),
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    )

    /** 一次「音频没了」类的内容错误（HTTP 404）。 */
    private fun contentNotFoundError(): PlaybackException {
        val invalid = HttpDataSource.InvalidResponseCodeException(
            404,
            "Not Found",
            IOException("HTTP 404"),
            emptyMap(),
            DataSpec(android.net.Uri.parse("https://example.com/audio.mp3")),
            ByteArray(0),
        )
        return PlaybackException(
            "音频不存在",
            invalid,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )
    }

    @Test
    fun `瞬时网络错误进入重连而不是直接失败`() {
        play()

        controller.playerListener.onPlayerError(transientNetworkError())

        assertEquals(
            "瞬时断流必须进入「正在重连」，不能进 ERROR —— 那正是原 bug",
            PlaybackState.RECOVERING,
            controller.state.value,
        )
        assertEquals("应该已经算作第 1 次重连", 1, controller.autoRecoveryAttempts)
        val message = controller.playbackError.value
        assertNotNull("重连期必须给出提示（否则用户以为死了）", message)
        assertTrue("提示应说明正在重连，实际是：$message", message!!.contains("重连"))
    }

    @Test
    fun `内容类错误不自动重连`() {
        play()

        controller.playerListener.onPlayerError(contentNotFoundError())

        assertEquals(
            "音频已经没了，重连一万次也是 404 —— 必须直接报错",
            PlaybackState.ERROR,
            controller.state.value,
        )
        assertEquals("不该有重连尝试", 0, controller.autoRecoveryAttempts)
    }

    @Test
    fun `重连次数用完后转为失败`() {
        play()

        // 连着失败 MAX 次（每次都进重连）。
        repeat(PlayerController.MAX_AUTO_RECOVERY_ATTEMPTS) {
            controller.playerListener.onPlayerError(transientNetworkError())
            assertEquals(PlaybackState.RECOVERING, controller.state.value)
        }
        assertEquals(
            PlayerController.MAX_AUTO_RECOVERY_ATTEMPTS,
            controller.autoRecoveryAttempts,
        )

        // 第 MAX+1 次：不再重连，如实报错。
        controller.playerListener.onPlayerError(transientNetworkError())

        assertEquals(
            "重连次数用完必须转为失败（不能让后台无限重试耗电）",
            PlaybackState.ERROR,
            controller.state.value,
        )
    }

    @Test
    fun `手动重试保留播放位置`() {
        play()
        controller.seekTo(120_000L)

        controller.notifyPlaybackError(IOException("断了"))
        assertEquals(PlaybackState.ERROR, controller.state.value)

        controller.retry()

        assertEquals(
            "用户听了 2 分钟再点重试，不能从头开始",
            120_000L,
            controller.position.value,
        )
    }

    @Test
    fun `退避第一次不延迟之后递增且封顶`() {
        assertEquals(
            "第一次要立刻重试（断流多半是瞬时的，顺带把掉出前台的窗口压到最短）",
            0L,
            controller.autoRecoveryDelayMs(0),
        )
        assertEquals(2_000L, controller.autoRecoveryDelayMs(1))
        assertEquals(4_000L, controller.autoRecoveryDelayMs(2))
        assertEquals(
            "退避必须有上限，不能越等越久",
            PlayerController.AUTO_RECOVERY_BACKOFF_MAX_MS,
            controller.autoRecoveryDelayMs(20),
        )
    }

    @Test
    fun `暂停会取消自动重连`() {
        play()
        controller.playerListener.onPlayerError(transientNetworkError())
        assertEquals(PlaybackState.RECOVERING, controller.state.value)

        controller.pause()

        assertEquals("用户主动暂停后不该再自己接上", PlaybackState.PAUSED, controller.state.value)
        assertEquals("重连计数也要清掉", 0, controller.autoRecoveryAttempts)
    }

    @Test
    fun `恢复成功后清掉提示并重置计数`() {
        play()
        controller.playerListener.onPlayerError(transientNetworkError())
        assertNotNull(controller.playbackError.value)

        // 声音真的又出来了（监听器回调）。
        controller.playerListener.onIsPlayingChanged(true)

        assertEquals(PlaybackState.PLAYING, controller.state.value)
        assertNull("声音都回来了还挂着「正在重连」就是撒谎", controller.playbackError.value)
        assertEquals("恢复成功要重置计数（下一轮抖动从头算）", 0, controller.autoRecoveryAttempts)
    }

    @Test
    fun `恢复前用户按了暂停就不再自动接上`() {
        play()
        controller.playerListener.onPlayerError(transientNetworkError())
        assertEquals(PlaybackState.RECOVERING, controller.state.value)

        // 用户在恢复体执行前按了暂停。
        controller.pause()

        // 推进时间：恢复体本该在这里执行。
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        org.robolectric.shadows.ShadowLooper.shadowMainLooper().idleFor(
            java.time.Duration.ofSeconds(30),
        )

        assertEquals(
            "用户按了暂停就别再自己放起来",
            PlaybackState.PAUSED,
            controller.state.value,
        )
    }

    @Test
    fun `暂停之后到来的错误不触发自动恢复`() {
        play()
        controller.pause()

        // 上一次 prepare 的加载失败回调在用户暂停之后才到 —— 这种情况很常见。
        controller.playerListener.onPlayerError(transientNetworkError())

        assertEquals(
            "用户已经按了暂停，后来的失败不该把声音又放起来",
            PlaybackState.PAUSED,
            controller.state.value,
        )
        assertEquals("不该有重连尝试", 0, controller.autoRecoveryAttempts)
    }
}
