package com.tingxia.audio.audio

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.tingxia.audio.data.local.FakePlaylistDao
import com.tingxia.audio.data.repository.PlaylistRepository
import kotlinx.coroutines.runBlocking
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

/**
 * 队列同步必须**保住「当前文章」**（2026-10-23）。
 *
 * ## 背景
 *
 * 仓库既有约定：**队列 = 播放列表 + 当前文章**（`buildQueue` 就是这么建的）。
 * 但 `syncPlayerPlaylist` 的目标集**只有播放列表** ——
 * 于是每次 `playlist_items` 变化，正在播的那一项（它不在播放列表表里）
 * 都会被当成「不在目标里的」而 `removeMediaItem` 掉。
 *
 * 后果：正在播的音频**直接断掉**，而界面还停在「正在播放」
 * （`onIsPlayingChanged` 里 STATE_IDLE 落到 `else -> Unit`，状态不变）
 * → 用户看到「暂停图标，却没声音」。
 *
 * ## 为什么这条容易回归
 *
 * 修法看着像「多拼一个 tail」，但只要有人以后把目标集改回「只有播放列表」，
 * 症状就会**原样复现** —— 而复现条件（当前文章不在播放列表里）是**常态**。
 *
 * ## 测试为什么要 sleep
 *
 * `setPlaylistRepository` 起的是 `Dispatchers.IO` 上的后台协程（真线程），
 * 它把播放列表灌进 PlayerController 的**镜像**（`_playlistItems`）。
 * `play()` 正是从那个镜像建队列的 —— 镜像还没灌好时调用，队列里就只有
 * 当前文章（不是 bug，是异步初始化的正常时序）。所以这里要等一下。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerControllerQueueTailTest {

    private lateinit var player: ExoPlayer
    private lateinit var controller: PlayerController
    private lateinit var dao: FakePlaylistDao
    private lateinit var repository: PlaylistRepository

    @Before
    fun setup() {
        player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        controller = PlayerController(player, RuntimeEnvironment.getApplication())
        dao = FakePlaylistDao()
        repository = PlaylistRepository(dao)
    }

    @After
    fun tearDown() {
        player.release()
    }

    private fun mediaIds(): List<String> =
        (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    private fun idle() {
        shadowOf(RuntimeEnvironment.getApplication().mainLooper).idle()
    }

    /** 等 observer 把播放列表灌进 controller 的镜像。 */
    private fun awaitPlaylistMirror() {
        repeat(20) {
            Thread.sleep(25)
            idle()
        }
    }

    @Test
    fun `播放列表变化时_正在播的那一篇不被删掉`() = runBlocking {
        // 播放列表里只有另一篇；当前播的是「不在播放列表里」的那篇
        repository.add("other", audioUrl = "https://example.com/other.mp3", title = "别的")
        controller.setPlaylistRepository(repository)
        awaitPlaylistMirror()

        controller.play(
            "https://example.com/current.mp3",
            title = "正在听的",
            articleId = "current",
        )
        // 队列 = 播放列表 + 当前文章
        assertEquals(listOf("other", "current"), mediaIds())

        // 播放列表变了（这里模拟「移除别的」→ 表 emit → 触发同步）
        repository.remove("other")
        awaitPlaylistMirror()

        assertTrue(
            "正在播的这一篇被同步逻辑删掉了：${mediaIds()}",
            mediaIds().contains("current"),
        )
    }

    @Test
    fun `当前文章在播放列表里时_同步不会重复添加`() = runBlocking {
        repository.add("a", audioUrl = "https://example.com/a.mp3", title = "A")
        controller.setPlaylistRepository(repository)
        awaitPlaylistMirror()

        controller.play(
            "https://example.com/a.mp3",
            title = "A",
            articleId = "a",
        )
        awaitPlaylistMirror()

        assertEquals(listOf("a"), mediaIds())
    }
}
