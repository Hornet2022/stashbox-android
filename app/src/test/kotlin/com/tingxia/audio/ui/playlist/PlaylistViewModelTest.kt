package com.tingxia.audio.ui.playlist

import androidx.media3.exoplayer.ExoPlayer
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.data.FakeArticleApi
import com.tingxia.audio.data.local.FakePlaylistDao
import com.tingxia.audio.data.model.AudioUrlResponse
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.model.DistillStatusResponse
import com.tingxia.audio.data.remote.ApiException
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.data.repository.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 播放列表 ViewModel 单测（2026-11-25 新增）。
 *
 * ## 为什么要有这份测试
 *
 * 内测反馈：「蒸馏中的内容加进播放列表，蒸馏完成后**还是**无法播放，
 * 提示没有音频」。
 *
 * 根因有两条，都不是「服务端没给地址」：
 *
 * 1. **补齐不被「蒸馏完成」驱动** —— 补齐逻辑挂在 Room 表变化上，
 *    而蒸馏完成不写本机表 ⇒ 补齐不会被触发。
 * 2. **补齐走的是 20 篇分页的列表接口** ⇒ 老文章永远匹配不上。
 *
 * ⇒ 修法：补齐与播放都改成**按 id 现取**单篇地址。
 *
 * 这份测试钉住的是「**行为契约**」，不是实现细节：
 * - 快照为空 + 已就绪 ⇒ **要播**（用现取到的地址）
 * - 快照为空 + 未就绪 ⇒ **不播** + 说清楚原因
 * - 快照非空 ⇒ **不额外发请求**（别为了「统一」把每次播放都变成一次网络往返）
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaylistViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var playerController: PlayerController
    private lateinit var dao: FakePlaylistDao

    /** 可控的假 API：能记调用次数、能模拟「还没蒸馏好」（抛 404 + code 40400）。 */
    private class RecordingArticleApi : FakeArticleApi() {
        var audioUrlCalls = 0
        var ready = false
        var url = "https://example.com/ready.mp3"

        /**
         * ⚠️ **必须抛真实形态**：`ApiException(http=404, biz=40400)`。
         *
         * 真实链路是 retrofit `HttpException` → `ApiEnvelope` 拦截器 → `ApiException`，
         * 而"还没好"的判据 `isAudioNotReady` 正是 **404 + 40400**。
         * 早先这里抛 `IllegalStateException` ⇒ 走不到分级分支 ⇒ 文案分级守卫会**假绿**。
         */
        override suspend fun getAudioUrl(id: String): AudioUrlResponse {
            audioUrlCalls++
            if (!ready) {
                throw ApiException(
                    httpCode = 404,
                    bizCode = 40400,
                    message = "audio not ready for article $id",
                )
            }
            return AudioUrlResponse(audio_url = url, durationSec = 120)
        }
    }

    private lateinit var api: RecordingArticleApi

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        playerController = PlayerController(
            ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build(),
            RuntimeEnvironment.getApplication(),
        )
        dao = FakePlaylistDao()
        api = RecordingArticleApi()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(): PlaylistViewModel = PlaylistViewModel(
        repository = PlaylistRepository(dao),
        playerController = playerController,
        articleRepository = ArticleRepository(api),
    )

    @Test
    fun `快照为空且已就绪_点播放要现取地址并播出去`() = runTest(testDispatcher) {
        // 加入时文章还在蒸馏 ⇒ 快照是空的
        dao.upsert(
            com.tingxia.audio.data.local.PlaylistItemEntity(
                articleId = "art_1",
                position = 0,
                title = "测试文章",
                audioUrl = null,
                durationSec = null,
            ),
        )
        // ⚠️ 必须**先建 VM 再置就绪**：
        //   建 VM 时 init 的补齐会跑一次 —— 若那时已就绪，地址会被补齐，
        //   `playFrom` 走的就是「快照非空」分支，**根本不会进现取分支**
        //   ⇒ 这条用例会变成假绿（变异打不掉）。
        //   真实场景是：页面开着、蒸馏后来完成、用户这时才点播放。
        api.ready = false
        api.url = "https://example.com/fetched.mp3"

        val vm = vm()
        testScope.advanceUntilIdle()

        // 蒸馏在这期间完成
        api.ready = true

        vm.playFrom(0)
        testScope.advanceUntilIdle()

        assertEquals(
            "快照为空时点播放必须现取地址并播出去",
            "https://example.com/fetched.mp3",
            playerController.currentAudioUrl.value,
        )
        assertEquals("art_1", playerController.currentArticleId.value)
    }

    @Test
    fun `快照为空且还没蒸馏好_不播且说清楚是还在蒸馏而不是没有`() = runTest(testDispatcher) {
        dao.upsert(
            com.tingxia.audio.data.local.PlaylistItemEntity(
                articleId = "art_2",
                position = 0,
                title = "测试文章",
                audioUrl = null,
                durationSec = null,
            ),
        )
        api.ready = false
        // 服务端说"还在蒸馏"（status 端点：distilling + stage=tts）
        api.statusResponses = mapOf(
            "art_2" to DistillStatusResponse(
                articleId = "art_2",
                status = DistillStatus.DISTILLING,
                stage = "tts",
            ),
        )

        val vm = vm()
        testScope.advanceUntilIdle()
        vm.playFrom(0)
        testScope.advanceUntilIdle()

        assertTrue(
            "还没蒸馏好时不该播（会播出一个空地址）",
            playerController.currentAudioUrl.value.isBlank(),
        )
        val error = vm.uiState.value.error
        assertNotNull(error, "必须给用户一个能看懂的原因")
        assertTrue(
            "提示必须说明是『还在蒸馏』（用户要知道自己等一下就行），实际是：$error",
            error!!.contains("蒸馏") || error.contains("合成语音"),
        )
        assertFalse(
            "不能把『还没好』说成『没有音频』——用户会以为永久不可用，实际是：$error",
            error.contains("没有音频"),
        )
    }

    @Test
    fun `蒸馏失败了_要说是没能生成而不是还在蒸馏`() = runTest(testDispatcher) {
        dao.upsert(
            com.tingxia.audio.data.local.PlaylistItemEntity(
                articleId = "art_failed",
                position = 0,
                title = "测试文章",
                audioUrl = null,
                durationSec = null,
            ),
        )
        api.ready = false
        // 服务端说"已经失败"（status 端点：failed）⇒ 等多久都没用
        api.statusResponses = mapOf(
            "art_failed" to DistillStatusResponse(
                articleId = "art_failed",
                status = DistillStatus.FAILED,
                error = "上游失败",
            ),
        )

        val vm = vm()
        testScope.advanceUntilIdle()
        vm.playFrom(0)
        testScope.advanceUntilIdle()

        val error = vm.uiState.value.error
        assertNotNull(error, "必须给用户一个能看懂的原因")
        assertTrue(
            "蒸馏失败要说『没能生成』并给出下一步，实际是：$error",
            error!!.contains("没能生成"),
        )
        assertFalse(
            "失败时不该说『还在蒸馏』（会让用户白等），实际是：$error",
            error.contains("还在蒸馏"),
        )
    }

    @Test
    fun `快照非空_点播放不额外发请求`() = runTest(testDispatcher) {
        dao.upsert(
            com.tingxia.audio.data.local.PlaylistItemEntity(
                articleId = "art_3",
                position = 0,
                title = "测试文章",
                audioUrl = "https://example.com/known.mp3",
                durationSec = 100,
            ),
        )

        val vm = vm()
        testScope.advanceUntilIdle()
        val callsAfterInit = api.audioUrlCalls

        vm.playFrom(0)
        testScope.advanceUntilIdle()

        assertEquals(
            "快照已经有地址时不该再发网络请求（别把每次播放都变成一次往返）",
            callsAfterInit,
            api.audioUrlCalls,
        )
        assertEquals(
            "https://example.com/known.mp3",
            playerController.currentAudioUrl.value,
        )
    }

    @Test
    fun `补齐不受文章列表分页限制_老文章也能拿到地址`() = runTest(testDispatcher) {
        // 这篇**不在** getArticles() 返回的列表里（老文章，被 20 篇分页截掉了）
        api.articles = emptyList()
        api.ready = true
        api.url = "https://example.com/old.mp3"

        dao.upsert(
            com.tingxia.audio.data.local.PlaylistItemEntity(
                articleId = "art_old",
                position = 0,
                title = "老文章",
                audioUrl = null,
                durationSec = null,
            ),
        )

        vm()
        testScope.advanceUntilIdle()

        val item = dao.byArticle("art_old")
        assertNotNull(item)
        assertEquals(
            "补齐必须按 id 单篇取 —— 靠 20 篇分页的列表接口会永远补不上老文章",
            "https://example.com/old.mp3",
            item!!.audioUrl,
        )
    }
}
