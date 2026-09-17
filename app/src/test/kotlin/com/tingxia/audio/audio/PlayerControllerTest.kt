package com.tingxia.audio.audio

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * PlayerController 纯逻辑单测（CP4.4）。
 *
 * 不真正创建 ExoPlayer（[PlayerController.initialize] 不调用，player == null），
 * 因此 [PlayerController.play] / [PlayerController.pause] / [PlayerController.stop] /
 * [PlayerController.seekTo] 只驱动状态机 + StateFlow，可在 JVM 下断言。
 * 真 ExoPlayer 播放走服务 / 集成测试（本期跳过，CP4.5 再补）。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerControllerTest {

    private lateinit var controller: PlayerController

    @Before
    fun setup() {
        controller = PlayerController(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `initial state is IDLE`() {
        assertEquals(PlaybackState.IDLE, controller.state.value)
    }

    @Test
    fun `play sets state to PLAYING`() {
        controller.play("https://example.com/a.mp3")
        assertEquals(PlaybackState.PLAYING, controller.state.value)
    }

    @Test
    fun `pause after play sets state to PAUSED`() {
        controller.play("https://example.com/a.mp3")
        controller.pause()
        assertEquals(PlaybackState.PAUSED, controller.state.value)
    }

    @Test
    fun `stop resets state and position`() {
        controller.play("https://example.com/a.mp3")
        controller.seekTo(5000L)
        controller.stop()
        assertEquals(PlaybackState.STOPPED, controller.state.value)
        assertEquals(0L, controller.position.value)
    }

    @Test
    fun `seekTo updates position`() {
        controller.seekTo(3000L)
        assertEquals(3000L, controller.position.value)
    }

    @Test
    fun `play sets media metadata for lock screen`() {
        // CP4.5：play() 应把 title/author/coverUrl 写入 ExoPlayer mediaItem 的 MediaMetadata，
        // 让锁屏 UI / MediaStyle 通知自动渲染。需真 ExoPlayer（initialize）。
        controller.initialize()
        controller.play(
            audioUrl = "https://example.com/a.mp3",
            title = "测试标题",
            author = "测试来源",
            coverUrl = "https://example.com/cover.png",
        )

        val playerField = PlayerController::class.java.getDeclaredField("player")
        playerField.isAccessible = true
        val player = playerField.get(controller) as ExoPlayer
        val md = player.currentMediaItem?.mediaMetadata

        assertEquals("测试标题", md?.title.toString())
        assertEquals("测试来源", md?.artist.toString())
        assertEquals(Uri.parse("https://example.com/cover.png"), md?.artworkUri)
    }
}
