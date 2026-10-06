package com.tingxia.audio.audio

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * 位置轮询泵的生命周期回归（2026-10-07 性能缺陷修复）。
 *
 * ## 缺陷
 * [PlayerController] 是 @Singleton，生命周期跟进程走，而它的 500ms 位置轮询
 * **无条件**自我重投（`postDelayed(this, POLL_INTERVAL_MS)`），类里没有任何
 * 生命周期观察者。后果：用户把 App 切到后台、音频还在放时，8 小时 = 57,600 次
 * 主线程唤醒，每次还顺带跑一遍 [PlayerController.checkListenCompletion]。
 *
 * ## 可观测信号怎么选
 * 不用 Handler 队列当断言对象 —— ExoPlayer 自己就有一个 100ms 的内部位置 tick，
 * [org.robolectric.shadows.ShadowLooper.getNextScheduledTaskTime] 永远先看到它，
 * 量出来的永远是 PT0.1S。
 *
 * 改为**让轮询本身产生可观测副作用**：用 [ControllablePositionPlayer] 包住真实
 * ExoPlayer，只接管 `getCurrentPosition()` / `getDuration()` 两个 getter，
 * 其余成员全部 `by` 委托给真实实例（于是播放/MediaSession 行为是真的）。
 * 测试推进假播放器的位置再 idle 主线程：
 * - `_position` 被刷新 → 这一跳跑了；
 * - `_position` 没动 → 这一跳**没有**跑。
 *
 * 注意 Robolectric 默认 [LooperMode.PAUSED]，`postDelayed` 不会自己跑，
 * 必须由测试显式推进 —— 这正好让"跑了几跳"成为完全确定的量。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerControllerPositionUpdatesTest {

    private lateinit var realPlayer: ExoPlayer
    private lateinit var fakePlayer: ControllablePositionPlayer
    private lateinit var controller: PlayerController

    @Before
    fun setup() {
        realPlayer = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        fakePlayer = ControllablePositionPlayer(realPlayer)
        controller = PlayerController(fakePlayer)
    }

    @After
    fun tearDown() {
        realPlayer.release()
        // 排干主线程：ExoPlayer 自己的 100ms 内部 tick 会一直重投，
        // 不 idle 的话 Robolectric 会在用例尾部抛 UnExecutedRunnablesException
        mainLooper.idle()
    }

    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    /** 推进主线程 [durationMs] 毫秒（让到期的轮询跳真正执行）。 */
    private fun idleMain(durationMs: Long) {
        mainLooper.idleFor(Duration.ofMillis(durationMs))
    }

    private fun play(articleId: String = "art_1") =
        controller.play(audioUrl = "https://example.com/audio.mp3", title = "t", articleId = articleId)

    // ── (a) 观察者进出 ─────────────────────────────────────────────────────

    @Test
    fun `无人观察时不再每 500ms 刷新位置`() {
        play()
        // 1 秒只够跑两跳 500ms；没人观察时不该有一跳发生
        fakePlayer.positionMs = 4_000L
        idleMain(1_000L)

        assertEquals(
            "没有观察者时 1 秒内不该有 500ms 轮询跑过（背景播放曾一晚 57600 次唤醒）",
            0L,
            controller.position.value,
        )
    }

    @Test
    fun `观察者注册后立刻恢复 500ms 刷新`() {
        play()
        fakePlayer.positionMs = 4_000L
        idleMain(1_000L)
        assertEquals(0L, controller.position.value) // 先确认确实是停的

        controller.startPositionUpdates()
        fakePlayer.positionMs = 7_000L
        idleMain(600L) // 只要一跳 500ms

        assertEquals(
            "注册观察者后应在一跳之内恢复刷新，不必等完后台慢跳",
            7_000L,
            controller.position.value,
        )
    }

    @Test
    fun `观察者离开后从 500ms 退回慢速轮询`() {
        play()
        controller.startPositionUpdates()
        fakePlayer.positionMs = 1_000L
        idleMain(600L)
        assertEquals(1_000L, controller.position.value)

        controller.stopPositionUpdates()
        fakePlayer.positionMs = 9_999L
        idleMain(2_000L) // 4 跳 500ms 的时间

        assertEquals(
            "观察者离开后不该再以 500ms 频率刷新",
            1_000L,
            controller.position.value,
        )
    }

    @Test
    fun `两个观察者并存时关掉一个不会停掉另一个`() {
        play()
        controller.startPositionUpdates() // 全屏播放器
        controller.startPositionUpdates() // 迷你条
        controller.stopPositionUpdates() // 只关掉其中一个

        fakePlayer.positionMs = 5_555L
        idleMain(600L)

        assertEquals(
            "观察者是计数不是布尔：关掉一个不能把另一个的更新也停掉",
            5_555L,
            controller.position.value,
        )
    }

    @Test
    fun `多退少退的注销不会把计数带成负数`() {
        play()
        controller.startPositionUpdates()
        repeat(3) { controller.stopPositionUpdates() } // 注销多于注册

        fakePlayer.positionMs = 3_333L
        idleMain(600L)
        assertEquals(0L, controller.position.value)

        // 计数已到 0，再注册一个仍然表示"有人在看"，刷新必须恢复
        controller.startPositionUpdates()
        fakePlayer.positionMs = 6_666L
        idleMain(600L)
        assertEquals(6_666L, controller.position.value)
    }

    // ── 慢跳保留下来的理由：非 UI 消费者 ───────────────────────────────────

    @Test
    fun `无人观察但还在播时保留慢跳以喂满听判定`() {
        play(articleId = "art_1")

        // listenCompleted 声明成 SharedFlow（有意不暴露 StateFlow 的读接口），
        // 所以这里收集而不是直接读 value。用 Unconfined 是为了让 idleMain 里
        // 主线程上的发射同步送达，不和 looper 推进抢时序。
        var listenCompletedAt: Long? = null
        val collector = CoroutineScope(Dispatchers.Unconfined).launch {
            controller.listenCompleted.collect { listenCompletedAt = it }
        }

        fakePlayer.durationMs = 10_000L
        fakePlayer.positionMs = 9_500L // ≥ 90%
        assertNull(listenCompletedAt)

        idleMain(10_500L) // 后台慢跳一跳

        assertNotNull(
            "App 在后台时音频播完，完听判定仍须触发（否则 listen-complete 不上报、评分卡不弹）",
            listenCompletedAt,
        )
        collector.cancel()
    }

    @Test
    fun `暂停且无人观察时轮询彻底停止`() {
        play()
        controller.pause()

        fakePlayer.positionMs = 7_777L
        idleMain(30_000L)

        assertEquals(
            "暂停后位置根本不会变，此时任何频率的轮询都是纯浪费",
            0L,
            controller.position.value,
        )
    }

    @Test
    fun `暂停时 UI 仍在观察则保留 500ms 以便拖动进度条`() {
        play()
        controller.startPositionUpdates()
        controller.pause()

        fakePlayer.positionMs = 8_888L
        idleMain(600L)

        assertEquals(
            "UI 还看着就得继续刷新，否则暂停态拖动进度条看不到位置",
            8_888L,
            controller.position.value,
        )
    }

    // ── (b) 播放与 MediaSession 不受轮询开关影响 ────────────────────────────

    @Test
    fun `停止位置更新不影响音频播放`() {
        play()
        controller.startPositionUpdates()
        controller.stopPositionUpdates() // 模拟 App 退到后台
        idleMain(5_000L) // 远超任何一个 500ms 窗口

        assertEquals(PlaybackState.PLAYING, controller.state.value)
        assertTrue("轮询停摆不能顺带把声音停了", realPlayer.playWhenReady)
        assertNotNull("MediaItem 必须还在", realPlayer.currentMediaItem)
    }

    @Test
    fun `停止位置更新不影响 MediaSession 与锁屏控制`() {
        play()
        controller.startPositionUpdates()
        controller.stopPositionUpdates()
        idleMain(5_000L)

        // 通知/锁屏进度走的是 Media3 自己的 DefaultMediaNotificationProvider →
        // 它渲染的是 MediaSession 绑定的这个 Player，与 positionHandler 无关。
        // 这里直接复现那条绑定，证明两者是各自独立的。
        // setId 必须给：MediaSession 的会话 id 全局唯一，同一 Robolectric 沙箱里
        // AudioPlayerServiceTest 已经用过默认（空）id，否则构造即抛
        // "Session ID must be unique"。
        val session = MediaSession.Builder(RuntimeEnvironment.getApplication(), realPlayer)
            .setId("player-position-updates-test")
            .setCallback(TingxiaMediaSessionCallback())
            .build()
        try {
            assertEquals(
                "MediaSession 必须仍绑在同一个共享 ExoPlayer 上（通知进度读的就是它）",
                realPlayer,
                session.player,
            )
            assertTrue(realPlayer.playWhenReady)

            // 锁屏上的暂停 / 续播仍然生效
            session.player.pause()
            assertTrue("轮询停止后锁屏暂停必须仍能控制播放", !realPlayer.playWhenReady)
            session.player.play()
            assertTrue("轮询停止后锁屏续播必须仍能控制播放", realPlayer.playWhenReady)
        } finally {
            session.release()
        }
    }

    @Test
    fun `resume 在界面可见时把 500ms 刷新接回来`() {
        play()
        controller.startPositionUpdates() // 迷你条 / 全屏播放器正显示
        controller.pause()
        controller.resume()

        fakePlayer.positionMs = 2_222L
        idleMain(600L)

        assertEquals(
            "续播后必须恢复刷新，否则进度条永远停在旧位置",
            2_222L,
            controller.position.value,
        )
    }

    @Test
    fun `resume 在无人观察时只恢复后台慢跳 不拉起 500ms 轮询`() {
        play()
        controller.pause()

        fakePlayer.positionMs = 1_111L
        idleMain(3_000L)
        assertEquals(0L, controller.position.value) // 暂停 + 无人看 = 彻底停表

        controller.resume()
        fakePlayer.positionMs = 2_222L
        idleMain(1_000L)
        assertEquals(
            "没有观察者时续播不该又回到 500ms 空转",
            0L,
            controller.position.value,
        )

        idleMain(10_000L)
        assertEquals(
            "但后台慢跳必须恢复，否则后台播放时断点续听读到的位置会冻住",
            2_222L,
            controller.position.value,
        )
    }
}

/**
 * 只接管位置/时长两个 getter 的 ExoPlayer 包装。
 *
 * 其余 ~70 个成员用 Kotlin 接口委托 `by` 转给真实实例，所以它**不是** mock：
 * `play()` / `pause()` / MediaSession 挂载走的都是真实 ExoPlayer，
 * 唯一被本测试接管的就是"当前播到哪儿了"—— 正好是轮询唯一读的东西。
 */
@UnstableApi
private class ControllablePositionPlayer(private val delegate: ExoPlayer) : ExoPlayer by delegate {
    var positionMs: Long = 0L
    var durationMs: Long = 0L

    override fun getCurrentPosition(): Long = positionMs

    /** 与真实 ExoPlayer 一致：未知时长是 [androidx.media3.common.C.TIME_UNSET]。 */
    override fun getDuration(): Long =
        if (durationMs <= 0L) androidx.media3.common.C.TIME_UNSET else durationMs
}