package com.tingxia.audio.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.tingxia.audio.audio.PlaybackState
import com.tingxia.audio.audio.PlayerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 迷你播放条 `AudioPlayerBar` 的 resume / play 分流回归。
 *
 * 背景（commit 记为「点续播总是从头开始」）：播放按钮里有一条分流 ——
 * 暂停态且当前曲目就是栏里这篇时调 `resume()`，否则调 `play()` 重建 MediaItem。
 * 分流写错不报错，只是位置归零：用户听到的是"我明明听到一半了"，很难归因，
 * 而且日志里连一行错误都没有。所以这里逐条钉住分流条件。
 *
 * 可观测信号的选择：`play()` 会写 `currentTitle` / `currentArticleId` 并重建
 * MediaItem，`resume()` 两者都不碰。所以「元数据有没有被改写」就是
 * 「走的是 play 还是 resume」的直接证据 —— 比断言 position 更稳
 * （position 只有 500ms 轮询才会刷新，测试里跑不到那一次）。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioPlayerBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var controller: PlayerController
    private var barClicked = false

    private val audioUrl = "https://example.com/audio.mp3"
    private val otherUrl = "https://example.com/other.mp3"

    @Before
    fun setup() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        controller = PlayerController(player)
        barClicked = false
    }

    private fun showBar(url: String?, title: String = "标题", articleId: String? = "art_1") {
        composeTestRule.setContent {
            MaterialTheme {
                AudioPlayerBar(
                    title = title,
                    audioUrl = url,
                    articleId = articleId,
                    playerController = controller,
                    onClick = { barClicked = true },
                )
            }
        }
    }

    /** 点播放/暂停按钮（contentDescription 随状态在「播放」/「暂停」之间切）。 */
    private fun clickPlayButton(expectPlaying: Boolean) {
        val label = if (expectPlaying) "暂停" else "播放"
        composeTestRule.onNodeWithContentDescription(label).performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `暂停态续播走 resume 不会从头开始`() {
        // 先播一次再暂停：currentAudioUrl / currentArticleId 都已落到这篇上
        controller.play(audioUrl = audioUrl, title = "原标题", articleId = "art_1")
        controller.seekTo(30_000L)
        controller.pause()
        assertEquals(PlaybackState.PAUSED, controller.state.value)

        showBar(audioUrl, title = "详情页显示的标题", articleId = "art_1")
        clickPlayButton(expectPlaying = false)

        assertEquals(PlaybackState.PLAYING, controller.state.value)
        // 走 resume 的证据：play() 会把这两个字段改写成栏里的值，resume() 不碰
        assertEquals("原标题", controller.currentTitle.value)
        assertEquals("art_1", controller.currentArticleId.value)
        // 位置不该被重置（走 play 的话轮询一跑就会拉回 0）
        assertEquals(30_000L, controller.position.value)
    }

    @Test
    fun `从未播过的新文章走 play 从头开始`() {
        // IDLE + currentAudioUrl 为空 → 不满足续播条件，必须 play
        assertEquals(PlaybackState.IDLE, controller.state.value)

        showBar(audioUrl, title = "新文章", articleId = "art_new")
        clickPlayButton(expectPlaying = false)

        assertEquals(PlaybackState.PLAYING, controller.state.value)
        assertEquals("新文章", controller.currentTitle.value)
        // articleId 必须传：漏了完听判定静默失效（评分卡不弹），见 PlayerController 说明
        assertEquals("art_new", controller.currentArticleId.value)
        assertEquals(0L, controller.position.value)
    }

    @Test
    fun `暂停着切到另一篇走 play 而不是续播旧曲`() {
        // 用户在 A 暂停后点开 B：最危险的退化是拿 A 的 MediaItem 直接 resume，
        // 结果播放的是 A，界面却显示 B
        controller.play(audioUrl = audioUrl, title = "A", articleId = "art_a")
        controller.pause()

        showBar(otherUrl, title = "B", articleId = "art_b")
        clickPlayButton(expectPlaying = false)

        assertEquals(PlaybackState.PLAYING, controller.state.value)
        assertEquals(otherUrl, controller.currentAudioUrl.value)
        assertEquals("B", controller.currentTitle.value)
        assertEquals("art_b", controller.currentArticleId.value)
    }

    @Test
    fun `IDLE 但 URL 相同也不续播而是重新 play`() {
        // release() 只把状态复位回 IDLE，currentAudioUrl 仍留着 —— 这时点播放
        // 必须重新 play，否则 ExoPlayer 已被 release 走，再 play() 也不会出声
        controller.play(audioUrl = audioUrl, title = "原标题", articleId = "art_1")
        controller.release()
        assertEquals(PlaybackState.IDLE, controller.state.value)
        assertEquals(audioUrl, controller.currentAudioUrl.value)

        showBar(audioUrl, title = "新标题", articleId = "art_1")
        clickPlayButton(expectPlaying = false)

        assertEquals(PlaybackState.PLAYING, controller.state.value)
        assertEquals("新标题", controller.currentTitle.value)
    }

    @Test
    fun `position 为 0 的暂停态仍然走 resume 不重新 play`() {
        // 位置存的就是 0（刚开头暂停）。分流条件只看「PAUSED + URL 相同」，
        // 不看 position —— 这里把现状钉住：pos=0 也走 resume。
        // 注意这与「0 应当当成无位置而重新 play」是相反的行为，
        // 若将来要改成后者，本用例会红，需同步改实现。
        controller.play(audioUrl = audioUrl, title = "原标题", articleId = "art_1")
        controller.pause()
        assertEquals(0L, controller.position.value)

        showBar(audioUrl, title = "另一个标题", articleId = "art_1")
        clickPlayButton(expectPlaying = false)

        assertEquals(PlaybackState.PLAYING, controller.state.value)
        assertEquals("原标题", controller.currentTitle.value)
    }

    @Test
    fun `播放中点按钮变成暂停`() {
        controller.play(audioUrl = audioUrl, title = "标题", articleId = "art_1")

        showBar(audioUrl)
        clickPlayButton(expectPlaying = true)

        assertEquals(PlaybackState.PAUSED, controller.state.value)
        // 暂停不该改写元数据（那是 play 的副作用）
        assertEquals("标题", controller.currentTitle.value)
    }

    @Test
    fun `没有音频 URL 时点播放是 no-op`() {
        // 蒸馏未就绪时 audioUrl 为 null：点一下不能崩，也不能把状态改成 PLAYING
        showBar(null)
        clickPlayButton(expectPlaying = false)

        assertEquals(PlaybackState.IDLE, controller.state.value)
        assertEquals("", controller.currentAudioUrl.value)
    }

    @Test
    fun `播放按钮的点击不冒泡到整栏 onClick`() {
        // 整栏点击是「跳全屏播放器」。播放按钮套在栏里，若冒泡，
        // 每次暂停/续播都会被弹去全屏页 —— 用户会觉得 App 在乱跳
        controller.play(audioUrl = audioUrl, title = "标题", articleId = "art_1")

        showBar(audioUrl)
        clickPlayButton(expectPlaying = true)

        assertFalse("播放按钮点击不应触发整栏 onClick", barClicked)
        assertEquals(PlaybackState.PAUSED, controller.state.value)
    }

    @Test
    fun `点标题区域才触发整栏 onClick`() {
        // 反向确认上一条：拦住冒泡的是「播放按钮」不是整栏本身。
        // 整栏点击是跳全屏播放器的唯一入口，被误伤的话迷你条就点不动了
        showBar(audioUrl, title = "可点的标题")
        composeTestRule.onNodeWithText("可点的标题").performClick()
        composeTestRule.waitForIdle()

        assertTrue("点标题区域应触发整栏 onClick", barClicked)
    }
}