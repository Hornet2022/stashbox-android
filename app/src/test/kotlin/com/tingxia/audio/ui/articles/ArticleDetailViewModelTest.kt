package com.tingxia.audio.ui.articles

import androidx.media3.exoplayer.ExoPlayer
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.data.FakeArticleApi
import com.tingxia.audio.data.FakeProgressApi
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.remote.ProgressGetResponse
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.data.repository.ProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ArticleDetailViewModel 单测（CP3.7.0 重构后）。
 *
 * 关键差异：
 * - VM 构造新增 ProgressRepository / ProgressApi / PlayerController
 * - 状态轮询迁新路径 §1.3 `GET /articles/{id}/status`
 * - 完听事件触发 §2.2 listen-complete 静默上报（不弹错误）
 * - 评分触发：手动调用 [ArticleDetailViewModel.rateArticle]
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArticleDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var playerController: PlayerController

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        playerController = PlayerController(ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeProgressRepo(getResponse: ProgressGetResponse = ProgressGetResponse(article_id = "a", position_sec = null, total_sec = null)): ProgressRepository =
        ProgressRepository(FakeProgressApi(getResponse, updateOk = true))

    private fun fakeVm(
        article: Article,
        initialStatus: DistillStatus = DistillStatus.READY,
        audioUrl: String = "https://example.com/$article-id.mp3",
        progressGet: ProgressGetResponse = ProgressGetResponse(article_id = article.id, position_sec = null, total_sec = null),
    ): ArticleDetailViewModel {
        val api = FakeArticleApi().apply {
            this.articles = listOf(article)
            this.audioUrls = mapOf(article.id to audioUrl)
            this.statuses = mapOf(article.id to initialStatus, "dst_${article.id}" to initialStatus)
        }
        return ArticleDetailViewModel(
            repository = ArticleRepository(api),
            progressRepository = fakeProgressRepo(progressGet),
            progressApi = FakeProgressApi(progressGet, true),
            playerController = playerController,
            context = RuntimeEnvironment.getApplication(),
        )
    }

    @Test
    fun loadArticle_ready_setsAudioUrlWithoutPolling() = testScope.runTest {
        val article = Article(
            id = "a",
            title = "t",
            status = DistillStatus.READY,
            audioUrl = "u",
            taskId = "t1",
        )
        val vm = fakeVm(article)
        vm.loadArticle("a")
        testScheduler.advanceUntilIdle()
        assertEquals(DistillStatus.READY, vm.uiState.value.status)
        assertEquals("u", vm.uiState.value.audioUrl)
    }

    @Test
    fun polling_untilReady_setsAudioUrl() = testScope.runTest {
        val article = Article(
            id = "a",
            title = "t",
            status = DistillStatus.DISTILLING,
            audioUrl = null,
            taskId = "t1",
        )
        // 第一次返回 DISTILLING,后续返回 READY
        val api = FakeArticleApi().apply {
            this.articles = listOf(article)
            this.statusResponses = mapOf(
                "a" to com.tingxia.audio.data.model.DistillStatusResponse(
                    articleId = "a",
                    status = DistillStatus.READY,
                    taskId = "t1",
                    audioUrl = "https://example.com/final.mp3",
                ),
            )
        }
        val vm = ArticleDetailViewModel(
            repository = ArticleRepository(api),
            progressRepository = fakeProgressRepo(),
            progressApi = FakeProgressApi(),
            playerController = playerController,
            context = RuntimeEnvironment.getApplication(),
        )
        vm.loadArticle("a")
        testScheduler.advanceUntilIdle()
        testScheduler.advanceTimeBy(ArticleDetailViewModel.POLL_INTERVAL_MS + 200)
        testScheduler.advanceUntilIdle()
        assertEquals(DistillStatus.READY, vm.uiState.value.status)
        assertNotNull(vm.uiState.value.audioUrl)
    }

    @Test
    fun loadArticle_failed_setsPollError() = testScope.runTest {
        val article = Article(
            id = "a",
            status = DistillStatus.DISTILLING,
            taskId = "t1",
        )
        val api = FakeArticleApi().apply {
            this.articles = listOf(article)
            this.statuses = mapOf("a" to DistillStatus.FAILED)
        }
        val vm = ArticleDetailViewModel(
            repository = ArticleRepository(api),
            progressRepository = fakeProgressRepo(),
            progressApi = FakeProgressApi(),
            playerController = playerController,
            context = RuntimeEnvironment.getApplication(),
        )
        vm.loadArticle("a")
        testScheduler.advanceUntilIdle()
        testScheduler.advanceTimeBy(ArticleDetailViewModel.POLL_INTERVAL_MS + 200)
        testScheduler.advanceUntilIdle()
        assertEquals(DistillStatus.FAILED, vm.uiState.value.status)
        assertEquals("蒸馏失败", vm.uiState.value.pollError)
    }

    @Test
    fun loadArticle_timeout_setsPollTimedOut() = testScope.runTest {
        val article = Article(
            id = "a",
            status = DistillStatus.DISTILLING,
            taskId = "t1",
        )
        val api = FakeArticleApi().apply {
            this.articles = listOf(article)
            // 永远 DISTILLING 不变
            this.statuses = mapOf("a" to DistillStatus.DISTILLING)
        }
        val vm = ArticleDetailViewModel(
            repository = ArticleRepository(api),
            progressRepository = fakeProgressRepo(),
            progressApi = FakeProgressApi(),
            playerController = playerController,
            context = RuntimeEnvironment.getApplication(),
        )
        vm.loadArticle("a")
        testScheduler.advanceUntilIdle()
        testScheduler.advanceTimeBy(
            ArticleDetailViewModel.POLL_INTERVAL_MS * (ArticleDetailViewModel.MAX_POLL_ATTEMPTS + 2),
        )
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.pollTimedOut)
    }

    @Test
    fun taskId_exposed_after_load() = testScope.runTest {
        val article = Article(
            id = "a",
            status = DistillStatus.READY,
            taskId = "dst_abc",
        )
        val vm = fakeVm(article)
        vm.loadArticle("a")
        testScheduler.advanceUntilIdle()
        assertEquals("dst_abc", vm.taskId.value)
    }

    @Test
    fun dismissEvaluationDialog_clearsFlag() = testScope.runTest {
        val article = Article(id = "a", status = DistillStatus.READY, taskId = "t")
        val vm = fakeVm(article)
        vm.loadArticle("a")
        testScheduler.advanceUntilIdle()
        // 不直接调用 listenCompleted（依赖 ExoPlayer 真实播放完成）
        // 仅验证 dismissEvaluationDialog 重置标志
        vm.dismissEvaluationDialog()
        assertEquals(false, vm.shouldShowEvaluationDialog.value)
    }

    @Test
    fun rateArticle_silentSucceeds() = testScope.runTest {
        val article = Article(id = "a", status = DistillStatus.READY)
        val vm = fakeVm(article)
        vm.loadArticle("a")
        testScheduler.advanceUntilIdle()
        // 不应抛异常,不依赖 UI state(评分是后台埋点)
        vm.rateArticle(4, "comment")
        testScheduler.advanceUntilIdle()
        assertEquals(DistillStatus.READY, vm.uiState.value.status)
    }

    @Test
    fun reportSkip_silentSucceeds() = testScope.runTest {
        val article = Article(id = "a", status = DistillStatus.READY)
        val vm = fakeVm(article)
        vm.loadArticle("a")
        testScheduler.advanceUntilIdle()
        vm.reportSkip("boring")
        testScheduler.advanceUntilIdle()
        // 不应崩
        assertEquals(DistillStatus.READY, vm.uiState.value.status)
    }
}