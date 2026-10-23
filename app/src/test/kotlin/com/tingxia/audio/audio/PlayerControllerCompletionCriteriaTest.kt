package com.tingxia.audio.audio

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
 * 完听判据（2026-10-23 新增）。
 *
 * ## 真机上发生了什么
 *
 * 已听完的剪藏内容再次打开 → 断点续听把进度恢复到**末尾** → 一打开就播完了
 * → 完听判定立刻成立 → **评价弹窗直接糊在脸上**，进度条一上来就在最右端。
 *
 * 根因：原来的判据只看「当前进度 ≥90% 或 已播完」，
 * 完全没有考虑「这一轮到底听没听」。
 *
 * ## 判据
 *
 * ① 位置到了（播完 / ≥90%）**且**
 * ② 这一轮真的推进了（从本次播放起点起至少 15s 或总时长的 5%）
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerControllerCompletionCriteriaTest {

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
    fun `恢复到末尾秒完不算听完`() {
        // 起点就是末尾（断点续听恢复到了结尾），本轮推进 0ms
        assertFalse(
            "恢复到末尾后秒完不该算听完",
            controller.shouldFireCompletion(
                positionMs = 317_000L,
                durationMs = 317_000L,
                ended = true,
                baselineMs = 317_000L,
            ),
        )
    }

    @Test
    fun `从头听完整篇算听完`() {
        // 起点 0，播到结尾 → 推进了整篇
        assertTrue(
            "从头听完必须算听完",
            controller.shouldFireCompletion(
                positionMs = 317_000L,
                durationMs = 317_000L,
                ended = true,
                baselineMs = 0L,
            ),
        )
    }

    @Test
    fun `差一点点没听完不算`() {
        // 起点 0，推进了 10 秒就结束了 → 不到 15s 门槛
        assertFalse(
            "只听了 10 秒不该算听完",
            controller.shouldFireCompletion(
                positionMs = 10_000L,
                durationMs = 317_000L,
                ended = true,
                baselineMs = 0L,
            ),
        )
    }

    @Test
    fun `长音频要听得更多才算听完`() {
        // 2 小时的音频：5% = 6 分钟，比 15s 门槛更高。
        // 起点放在 85%（这样位置能到 90%，测的纯粹是「推进量」这道门槛）。
        val twoHours = 7_200_000L
        val baseline = 6_120_000L          // 85%
        assertFalse(
            "长音频只推进 2.5%（3 分钟）不该算听完",
            controller.shouldFireCompletion(
                positionMs = 6_300_000L,     // 87.5%
                durationMs = twoHours,
                ended = false,
                baselineMs = baseline,
            ),
        )
        assertTrue(
            "长音频推进够 5%（6 分钟）就算听完",
            controller.shouldFireCompletion(
                positionMs = 6_480_000L,     // 90%
                durationMs = twoHours,
                ended = false,
                baselineMs = baseline,
            ),
        )
    }

    @Test
    fun `位置没到就不算听完`() {
        // 推进够了但只听到 50%
        assertFalse(
            "没听到 90% 不该算听完",
            controller.shouldFireCompletion(
                positionMs = 158_500L,
                durationMs = 317_000L,
                ended = false,
                baselineMs = 0L,
            ),
        )
    }

    @Test
    fun `从 80 续听到结尾算听完`() {
        // 断点续听：起点 80%，听完了最后 20% → 推进了 63s > 门槛 → 算听完
        assertTrue(
            "续听听完最后一段应该算听完",
            controller.shouldFireCompletion(
                positionMs = 317_000L,
                durationMs = 317_000L,
                ended = true,
                baselineMs = 253_600L,
            ),
        )
    }
}
