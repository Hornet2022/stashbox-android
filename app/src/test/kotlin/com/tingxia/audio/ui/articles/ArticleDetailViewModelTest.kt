package com.tingxia.audio.ui.articles

import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.model.ArticleListResponse
import com.tingxia.audio.data.model.AudioUrlResponse
import com.tingxia.audio.data.model.DistillStatusResponse
import com.tingxia.audio.data.model.RetryResponse
import com.tingxia.audio.data.remote.ArticleApi
import com.tingxia.audio.data.repository.ArticleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArticleDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeRepo(
        article: Article,
        distillStatus: DistillStatus = DistillStatus.READY,
        audioUrl: String = "https://example.com/final.mp3",
    ) = ArticleRepository(object : ArticleApi {
        override suspend fun getArticles() = ArticleListResponse(emptyList())
        override suspend fun getArticle(id: String) = article
        override suspend fun getDistillStatus(taskId: String) =
            DistillStatusResponse(task_id = taskId, status = distillStatus)
        override suspend fun getAudioUrl(id: String) = AudioUrlResponse(audio_url = audioUrl)
        // CP5.2-A: 满足 ArticleApi retryArticle 抽象方法（这些测试不测 retry）
        override suspend fun retryArticle(id: String) =
            RetryResponse(article_id = id, status = "pending", retry_count = 0, queued_at = "", distill_triggered = false)
    })

    private fun fakeController() = PlayerController(RuntimeEnvironment.getApplication())

    @Test
    fun loadArticle_ready_setsAudioUrlWithoutPolling() = testScope.runTest {
        val article = Article(
            id = "a",
            title = "t",
            status = DistillStatus.READY,
            audioUrl = "u",
            taskId = "t1",
        )
        val vm = ArticleDetailViewModel(fakeRepo(article, DistillStatus.READY, "u"), fakeController())
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
            taskId = "t1",
        )
        val vm = ArticleDetailViewModel(fakeRepo(article, DistillStatus.READY, "https://x/final.mp3"), fakeController())
        vm.loadArticle("a")
        testScheduler.advanceUntilIdle() // loadArticle 完成，轮询已启动
        testScheduler.advanceTimeBy(ArticleDetailViewModel.POLL_INTERVAL_MS + 200)
        testScheduler.advanceUntilIdle()
        assertEquals(DistillStatus.READY, vm.uiState.value.status)
        assertEquals("https://x/final.mp3", vm.uiState.value.audioUrl)
    }

    @Test
    fun polling_timeout_setsTimedOut() = testScope.runTest {
        val article = Article(
            id = "a",
            title = "t",
            status = DistillStatus.DISTILLING,
            taskId = "t1",
        )
        // 蒸馏状态一直停留在 DISTILLING → 超过最大次数后超时
        val vm = ArticleDetailViewModel(fakeRepo(article, DistillStatus.DISTILLING, "u"), fakeController())
        vm.loadArticle("a")
        testScheduler.advanceUntilIdle()
        testScheduler.advanceTimeBy(
            ArticleDetailViewModel.POLL_INTERVAL_MS * (ArticleDetailViewModel.MAX_POLL_ATTEMPTS + 2),
        )
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.pollTimedOut)
    }
}
