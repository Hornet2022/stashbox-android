package com.tingxia.audio.ui.articles

import androidx.media3.exoplayer.ExoPlayer
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.data.FakeArticleApi
import com.tingxia.audio.data.FakeDistillationApi
import com.tingxia.audio.data.FakeProgressApi
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.model.RetryResponse
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.data.repository.EvaluationRepository
import com.tingxia.audio.data.local.InMemoryPlaybackProgressDao
import com.tingxia.audio.data.repository.ProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CP5.2-A + CP3.7.0: retryArticle 单元测试
 *
 * 测试 retryState 状态机。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])


class ArticleDetailRetryTest {

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
        retryResponse: RetryResponse = RetryResponse(
            article_id = article.id,
            status = "pending",
            retry_count = 1,
            queued_at = "2026-01-01T00:00:00Z",
            distill_triggered = true,
        ),
    ): ArticleRepository = ArticleRepository(
        object : com.tingxia.audio.data.remote.ArticleApi by FakeArticleApi() {
            override suspend fun retryArticle(id: String): RetryResponse = retryResponse
        }
    )

    private fun fakeController(): PlayerController =
        PlayerController(ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build())

    private fun fakeVm(
        article: Article,
        retryResponse: RetryResponse = RetryResponse(
            article_id = article.id,
            status = "pending",
            retry_count = 1,
            queued_at = "2026-01-01T00:00:00Z",
            distill_triggered = true,
        ),
    ): ArticleDetailViewModel {
        val api = FakeArticleApi().apply {
            this.articles = listOf(article)
            this.statuses = mapOf(article.id to DistillStatus.FAILED)
        }
        val repo = ArticleRepository(
            object : com.tingxia.audio.data.remote.ArticleApi by api {
                override suspend fun retryArticle(id: String): RetryResponse = retryResponse
            }
        )
        return ArticleDetailViewModel(
            repository = repo,
            progressRepository = ProgressRepository(FakeProgressApi(), InMemoryPlaybackProgressDao()),
            progressApi = FakeProgressApi(),
            playerController = fakeController(),
            evaluationRepository = EvaluationRepository(FakeDistillationApi()),
            context = RuntimeEnvironment.getApplication(),
        )
    }

    @Test
    fun test_retryArticle_sendsRequest_andUpdatesState() = testScope.runTest {
        val article = Article(
            id = "a1",
            title = "Test Article",
            status = DistillStatus.FAILED,
            taskId = "t1",
        )
        val vm = fakeVm(article)
        vm.loadArticle("a1")
        testScheduler.advanceUntilIdle()
        vm.retryArticle("a1")
        testScheduler.advanceUntilIdle()
        val state = vm.retryState.value
        assertTrue("expected Success, got $state", state is RetryState.Success)
    }

    @Test
    fun test_retryArticle_errorState() = testScope.runTest {
        val article = Article(id = "a1", status = DistillStatus.FAILED, taskId = "t1")
        val vm = ArticleDetailViewModel(
            repository = ArticleRepository(
                object : com.tingxia.audio.data.remote.ArticleApi by FakeArticleApi() {
                    override suspend fun retryArticle(id: String): RetryResponse =
                        throw RuntimeException("retry endpoint down")
                }
            ),
            progressRepository = ProgressRepository(FakeProgressApi(), InMemoryPlaybackProgressDao()),
            progressApi = FakeProgressApi(),
            playerController = fakeController(),
            evaluationRepository = EvaluationRepository(FakeDistillationApi()),
            context = RuntimeEnvironment.getApplication(),
        )
        vm.loadArticle("a1")
        testScheduler.advanceUntilIdle()
        vm.retryArticle("a1")
        testScheduler.advanceUntilIdle()
        assertTrue(vm.retryState.value is RetryState.Error)
    }
}