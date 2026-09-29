package com.tingxia.audio.audio

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `PlayerController.currentArticleId` 的回归（完听判定的前提条件）。
 *
 * 真机症状：`feedback` 表 `type=listen_complete` 至今只有 2 条、停在 9-20，
 * 而 `audio_play_start` 有 71 条 —— 播放正常上报，完听上报全丢。
 *
 * 根因是 `currentArticleId` 曾经是私有裸字段，靠调用方在 `play()` 之前
 * **另外**调 `setCurrentArticleId()` 赋值，而全项目 4 个 `play()` 调用点
 * 只有 2 个记得调：
 * - `AudioPlayerBar`（详情页迷你播放条）漏调
 * - `FullScreenPlayerScreen`（全屏播放器恢复路径）漏调
 *
 * 漏调不报错，是**静默失灵**：
 * - 为 null → `checkListenCompletion()` 首行 return → `listenCompleted` 永不发射
 *   → listen-complete 不上报 → 听感评分卡永不自动弹
 * - 为上一首的残留值 → 完听被报到错误 article_id 上
 *
 * 现在 `play()` 自己接管 `articleId`，本测试锁住这个不变量。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerArticleIdTest {

    private lateinit var controller: PlayerController

    @Before
    fun setup() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        controller = PlayerController(player)
    }

    @Test
    fun `初始没有当前文章`() {
        assertNull(controller.currentArticleId.value)
    }

    @Test
    fun `play 传 articleId 会设置当前文章`() {
        controller.play(
            audioUrl = "https://example.com/a.mp3",
            title = "A",
            articleId = "art_a",
        )
        assertEquals("art_a", controller.currentArticleId.value)
    }

    @Test
    fun `连续播放不同文章时 currentArticleId 跟着切`() {
        // 旧实现下第二次 play 不带 articleId → 残留 art_a，
        // 完听会把 B 的完成报到 A 上
        controller.play(audioUrl = "https://example.com/a.mp3", title = "A", articleId = "art_a")
        controller.play(audioUrl = "https://example.com/b.mp3", title = "B", articleId = "art_b")
        assertEquals("art_b", controller.currentArticleId.value)
    }

    @Test
    fun `play 不传 articleId 时沿用旧值而不是清空`() {
        // 全屏播放器的 IDLE 恢复路径就是这种调用：文章没变，
        // 传 null 表示"沿用"，清空会让完听判定失效
        controller.play(audioUrl = "https://example.com/a.mp3", title = "A", articleId = "art_a")
        controller.play(audioUrl = "https://example.com/a.mp3", title = "A")
        assertEquals("art_a", controller.currentArticleId.value)
    }

    @Test
    fun `setCurrentArticleId 仍可用于显式覆盖`() {
        controller.play(audioUrl = "https://example.com/a.mp3", title = "A", articleId = "art_a")
        controller.setCurrentArticleId("art_b")
        assertEquals("art_b", controller.currentArticleId.value)
    }
}
