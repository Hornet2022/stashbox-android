package com.tingxia.audio.audio

import androidx.media3.common.util.UnstableApi
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

/**
 * 播放错误反馈 + 睡眠定时器（2026-10-23 新增）。
 *
 * ## 为什么要有这些
 *
 * 此前**全项目零 `Player.Listener`**，而播放状态是 `play()` 里乐观设成
 * `PLAYING` 的 —— 播放失败（网络断 / 音频 404 / 解码错）时界面毫无反应：
 * 进度条不动、按钮还是「暂停」、没有任何提示。用户只看到「按了没声音」。
 *
 * 睡眠定时器是音频 App 的标配，且它必须活得比界面久（页面被销毁 → 定时器
 * 跟着没了 → 用户在后台听着，第二天早上还在放），所以放在**播放器**这一层。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerControllerErrorAndSleepTimerTest {

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
    }

    @Test
    fun `播放失败时状态变成 ERROR 且给出可读文案`() {
        controller.play(
            audioUrl = "https://example.com/a.mp3",
            title = "A",
            articleId = "art_a",
        )
        // 直接把错误抛进 ExoPlayer：用一个必定失败的 URI（Robolectric 下
        // 不会有真实网络请求，但 prepare 会因为无法解析而报错）。
        // 为了让断言确定，这里不依赖真实网络，而是调用 controller 暴露的
        // 错误处理入口 —— 它与 Player.Listener.onPlayerError 是同一条路径。
        controller.notifyPlaybackError(IOException("网络不通"))

        assertEquals(PlaybackState.ERROR, controller.state.value)
        val message = controller.playbackError.value
        assertNotNull("失败必须给出可读文案", message)
        assertTrue(
            "文案应来自仓内统一的 friendlyError（网络类 → 「网络不给力」）" +
                "，实际是：$message",
            message!!.contains("网络") || message.contains("播放失败"),
        )
    }

    @Test
    fun `重试会清掉错误并回到播放态`() {
        controller.play(
            audioUrl = "https://example.com/a.mp3",
            title = "A",
            articleId = "art_a",
        )
        controller.notifyPlaybackError(IOException("网络不通"))
        assertEquals(PlaybackState.ERROR, controller.state.value)

        controller.retry()

        assertEquals(PlaybackState.PLAYING, controller.state.value)
        assertNull("重试后不该还挂着错误文案", controller.playbackError.value)
    }

    @Test
    fun `没有可重播的地址时回到 IDLE 而不是继续报错`() {
        // 什么都没播过 → retry 不该崩，也不该假装成功
        controller.retry()
        assertEquals(PlaybackState.IDLE, controller.state.value)
        assertNull(controller.playbackError.value)
    }

    @Test
    fun `睡眠定时器设置后有倒计时分钟数`() {
        assertEquals(0, controller.sleepTimerMinutes.value)
        controller.setSleepTimerMinutes(30)
        assertEquals(30, controller.sleepTimerMinutes.value)
    }

    @Test
    fun `睡眠定时器设为 0 即取消`() {
        controller.setSleepTimerMinutes(30)
        controller.setSleepTimerMinutes(0)
        assertEquals(0, controller.sleepTimerMinutes.value)
    }

    @Test
    fun `睡眠定时器到点自动暂停`() {
        controller.play(
            audioUrl = "https://example.com/a.mp3",
            title = "A",
            articleId = "art_a",
        )
        controller.setSleepTimerMinutes(1)
        assertEquals(PlaybackState.PLAYING, controller.state.value)

        // 推进协程的延迟：定时器用的是 progressScope（Dispatchers.IO），
        // 这里用 Robolectric 的时间推进 + idle 让它跑完。
        // 定时器挂在主线程 Handler 上 → 用 ShadowLooper 确定性推进 61 秒。
        // （用协程 delay 做不到确定性：Dispatchers.IO 不是虚拟时钟。）
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        org.robolectric.shadows.ShadowLooper.shadowMainLooper().idleFor(
            java.time.Duration.ofSeconds(61),
        )

        assertEquals(PlaybackState.PAUSED, controller.state.value)
        assertEquals(0, controller.sleepTimerMinutes.value)
    }
}
