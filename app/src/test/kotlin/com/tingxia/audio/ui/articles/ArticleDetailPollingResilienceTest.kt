package com.tingxia.audio.ui.articles

import androidx.media3.exoplayer.ExoPlayer
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.data.FakeArticleApi
import com.tingxia.audio.data.FakeDistillationApi
import com.tingxia.audio.data.FakeProgressApi
import com.tingxia.audio.data.local.InMemoryPlaybackProgressDao
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.AudioUrlResponse
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.model.DistillStatusResponse
import com.tingxia.audio.data.remote.ProgressGetResponse
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.data.repository.EvaluationRepository
import com.tingxia.audio.data.repository.ProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

/**
 * 蒸馏轮询的容错（2026-10-03 修）。
 *
 * 修之前：`startPolling` 的 catch 里直接 `return@launch`，一次网络失败就永久
 * 终止轮询，且没有重试入口。「剪藏 → 等 12~23 分钟 → 收听」是主流程，一次地铁
 * /电梯的基站切换就让它永远停在「处理中」—— 服务端其实正常跑完了，但页面没有
 * 错误、没有重试、音频栏不出现。
 *
 * 修之后：单次失败只记一次并继续下一轮；连续失败超阈值才放弃，且给出可重试提示。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArticleDetailPollingResilienceTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var playerController: PlayerController

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        playerController = PlayerController(
            ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** 前 failFirst 次 getArticleStatus 抛异常，之后按 statusById 返回。 */
    private class FlakyApi(
        private val failFirst: Int,
        private val statusById: Map<String, DistillStatus>,
        private val urlById: Map<String, String>,
    ) : FakeArticleApi() {
        var calls = 0
            private set

        override suspend fun getArticleStatus(id: String): DistillStatusResponse {
            calls++
            if (calls <= failFirst) throw java.io.IOException("模拟网络抖动 #$calls")
            return DistillStatusResponse(
                articleId = id,
                status = statusById[id] ?: DistillStatus.READY,
                taskId = "dst_$id",
            )
        }

        override suspend fun getAudioUrl(id: String) =
            AudioUrlResponse(audio_url = urlById[id] ?: "u")

        override suspend fun getArticle(id: String) = Article(
            id = id, title = "t", status = DistillStatus.DISTILLING, audioUrl = null, taskId = "dst_$id",
        )
    }

    private fun vm(api: FakeArticleApi) = ArticleDetailViewModel(
        repository = ArticleRepository(api),
        progressRepository = ProgressRepository(
            FakeProgressApi(ProgressGetResponse(article_id = "a", position_sec = null, total_sec = null), true),
            InMemoryPlaybackProgressDao(),
        ),
        progressApi = FakeProgressApi(
            ProgressGetResponse(article_id = "a", position_sec = null, total_sec = null), true,
        ),
        playerController = playerController,
        evaluationRepository = EvaluationRepository(FakeDistillationApi()),
        context = RuntimeEnvironment.getApplication(),
    )

    @Test
    fun 单次网络抖动后轮询继续并最终拿到音频() = testScope.runTest {
        // 第一次失败，第二次就 READY —— 旧代码在这里就永久放弃了
        val api = FlakyApi(
            failFirst = 1,
            statusById = mapOf("a" to DistillStatus.READY),
            urlById = mapOf("a" to "https://cdn/a.mp3"),
        )
        val vm = vm(api)
        vm.loadArticle("a")

        // 走完 3s 间隔 + 一次失败 + 3s 间隔 + 一次成功
        advanceTimeBy(ArticleDetailViewModel.POLL_INTERVAL_MS * 2 + 500)
        testScheduler.advanceUntilIdle()

        assertEquals(
            "抖动后应该继续轮询并拿到 READY",
            DistillStatus.READY, vm.uiState.value.status,
        )
        assertEquals("https://cdn/a.mp3", vm.uiState.value.audioUrl)
    }

    @Test
    fun 连续失败超阈值才放弃_并保留可重试提示() = testScope.runTest {
        // 一直失败，超过阈值才停
        val api = FlakyApi(
            failFirst = Int.MAX_VALUE,
            statusById = mapOf("a" to DistillStatus.READY),
            urlById = emptyMap(),
        )
        val vm = vm(api)
        vm.loadArticle("a")

        advanceTimeBy(ArticleDetailViewModel.POLL_INTERVAL_MS * 6)
        testScheduler.advanceUntilIdle()

        val err = vm.uiState.value.pollError
        assertNotNull("放弃时必须给出错误提示，不能静默停止", err)
        assertTrue(
            "提示里应说明是连续失败并可重试，实际：$err",
            err!!.contains("连续") && err.contains("重试"),
        )
    }

    @Test
    fun 失败次数未到阈值时不放弃_仍在继续轮询() = testScope.runTest {
        // 失败 2 次（阈值 3），第三次成功
        val api = FlakyApi(
            failFirst = 2,
            statusById = mapOf("a" to DistillStatus.READY),
            urlById = mapOf("a" to "https://cdn/a.mp3"),
        )
        val vm = vm(api)
        vm.loadArticle("a")

        advanceTimeBy(ArticleDetailViewModel.POLL_INTERVAL_MS * 4)
        testScheduler.advanceUntilIdle()

        assertEquals(3, api.calls)
        assertEquals(DistillStatus.READY, vm.uiState.value.status)
    }

    @Test
    fun 成功一次会重置失败计数_不会因抖动误判为不可达() = testScope.runTest {
        // 失败、成功、失败、成功、失败、成功 … 交替，且**永远不返回 READY**
        // （返回 READY 会正常退出轮询，就测不到计数重置了）。
        // 若失败计数只累加不清零，第 3 次失败时就会累计到阈值而放弃；
        // 实际每次失败之间都有成功，服务一直是可达的。
        val api = AlternatingApi()
        val vm = vm(api)
        vm.loadArticle("a")

        advanceTimeBy(ArticleDetailViewModel.POLL_INTERVAL_MS * 5)
        testScheduler.advanceUntilIdle()

        assertTrue(
            "应该持续在轮询，实际只调了 ${api.calls} 次",
            api.calls >= 3,
        )
        // 关键断言：交替模式下每次失败之间都有成功，所以**不该**触发
        // 「连续 N 次无法连接」的放弃路径。轮询会一直跑到 30 次上限正常超时。
        val err = vm.uiState.value.pollError
        assertTrue(
            "不应因抖动放弃轮询（不应出现「连续 N 次无法连接」），实际 err=$err",
            err == null || !err.contains("连续"),
        )
    }

    /**
     * 奇数次失败、偶数次成功，且成功时返回 DISTILLING 让轮询继续。
     * 用来验证「成功一次就重置连续失败计数」。
     */
    private class AlternatingApi : FakeArticleApi() {
        var calls = 0
            private set

        override suspend fun getArticleStatus(id: String): DistillStatusResponse {
            calls++
            // 第 1、3、5… 次失败；第 2、4、6… 次成功
            if (calls % 2 == 1) throw java.io.IOException("偶发抖动")
            return DistillStatusResponse(
                articleId = id,
                status = DistillStatus.DISTILLING, // 继续轮询，不退出
                taskId = "dst_$id",
            )
        }

        override suspend fun getArticle(id: String) = Article(
            id = id, title = "t", status = DistillStatus.DISTILLING, audioUrl = null, taskId = "dst_$id",
        )
    }
}
