package com.tingxia.audio.audio

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 换曲时字幕必须跟着换（2026-10-23 新增）。
 *
 * ## 真机上发生了什么
 *
 * 播放列表里放了两篇，播完第一篇**自动续播**到第二篇：
 * - 标题切成了《通宵折腾 DeepSeek Harness…》（对）
 * - 时长切成了 05:17（对）
 * - **字幕还在显示上一篇的稿子**：「QwenGyre 的核心思路是把「干活」和「学习」解耦。」
 *
 * 根因：字幕稿的切换只写在 [PlayerController.play] 里，而**队列自动续播
 * 不会调用 play()** —— 它走的是 ExoPlayer 自己的 MediaItem 切换。
 * 于是稿子没换，而时间轴又会按**新时长**重算 → 屏幕上是
 * 「上一篇的内容 + 这一篇的时间」，两者毫无对应关系。
 *
 * 这是典型的「改 A 处忘了 B 处」：换曲有两条入口，只在一条里补了逻辑。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerControllerSubtitleSwitchTest {

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

    private fun mediaItem(articleId: String, title: String) = MediaItem.Builder()
        .setUri("https://example.com/$articleId.mp3")
        .setMediaId(articleId)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .build()

    @Test
    fun `队列自动续播换到下一篇时字幕稿跟着换`() {
        controller.setSubtitleScript("art_a", "这是第一篇的稿子。")
        controller.play(audioUrl = "https://example.com/art_a.mp3", title = "A", articleId = "art_a")
        assertEquals("这是第一篇的稿子。", controller.currentSubtitleScript)

        // 第二篇的稿子已经缓存过（详情页推过来，或之前取过）
        controller.setSubtitleScript("art_b", "这是第二篇的稿子。")

        // 模拟队列自动续播：ExoPlayer 自己切到了下一项
        controller.handleMediaItemTransition(mediaItem("art_b", "B"))

        assertEquals(
            "换曲后字幕稿必须换成当前这篇的",
            "这是第二篇的稿子。",
            controller.currentSubtitleScript,
        )
    }

    @Test
    fun `换到没有稿子的文章时不能沿用上一篇的稿子`() {
        controller.setSubtitleScript("art_a", "这是第一篇的稿子。")
        controller.play(audioUrl = "https://example.com/art_a.mp3", title = "A", articleId = "art_a")
        assertEquals("这是第一篇的稿子。", controller.currentSubtitleScript)

        // 第二篇没有稿子（取不到）：此时**必须没有字幕**，
        // 而不是拿上一篇的稿子配新时长重算时间轴 ——
        // 那会让屏幕上出现「上一篇的内容配这一篇的时间」。
        controller.handleMediaItemTransition(mediaItem("art_b", "B"))

        assertNull(
            "换到没有稿子的文章时不该沿用上一篇的稿子",
            controller.currentSubtitleScript,
        )
    }
}
