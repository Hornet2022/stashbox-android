package com.tingxia.audio.audio

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「上一首/下一首」按钮的可用状态（2026-10-23 新增）。
 *
 * ## 用户的原话
 *
 * 「耳机按键的『上一首/下一首』要等队列里真有下一项才亮」。
 *
 * 系统媒体控件（通知栏 / 锁屏 / 耳机按键 / 系统媒体面板）本来就由 Media3
 * 按 `player.getAvailableCommands()` 自动处理 —— 队列里没有下一项时
 * Media3 就不提供 `COMMAND_SEEK_TO_NEXT`，那颗按钮自然不出现。
 *
 * 但**App 内**全屏播放器上那两颗按钮是自己画的：之前它们**永远亮着**，
 * 到末尾点下去毫无反应（`playNext()` 返回 false，返回值被丢掉了）。
 * 一个看得见却点不动的按钮会让人以为是 App 坏了。
 *
 * ## 为什么真相来源是播放器
 *
 * 播列表归播放器之后，系统媒体面板、耳机按键、通知栏按钮都能换曲，
 * App 侧再维护一份游标就必然和播放器对不上 —— 两套状态迟早漂移，
 * 而漂移的表象是「按钮亮着但点了没反应」。
 *
 * 所以边界状态直接读 `player.hasNextMediaItem()` / `hasPreviousMediaItem()`。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerControllerQueueBoundsTest {

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

    private fun item(id: String) = MediaItem.Builder()
        .setUri("https://example.com/$id.mp3")
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(id).build())
        .build()

    @Test
    fun `队列只有一首时上一首和下一首都不可用`() {
        player.setMediaItems(listOf(item("a")), 0, 0L)
        player.prepare()

        controller.handleMediaItemTransition(item("a"))

        assertFalse("只有一首时不该有下一首", controller.hasNextItem.value)
        assertFalse("只有一首时不该有上一首", controller.hasPreviousItem.value)
    }

    @Test
    fun `在队列中间时上一首和下一首都可用`() {
        player.setMediaItems(listOf(item("a"), item("b"), item("c")), 1, 0L)
        player.prepare()

        controller.handleMediaItemTransition(item("b"))

        assertTrue("中间位置应该有下一首", controller.hasNextItem.value)
        assertTrue("中间位置应该有上一首", controller.hasPreviousItem.value)
    }

    @Test
    fun `在队列末尾时下一首不可用`() {
        player.setMediaItems(listOf(item("a"), item("b")), 1, 0L)
        player.prepare()

        controller.handleMediaItemTransition(item("b"))

        assertFalse("末尾不该再有下一首", controller.hasNextItem.value)
        assertTrue("末尾应该还有上一首", controller.hasPreviousItem.value)
    }
}
