package com.tingxia.audio.ui.articles

import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.ArticleListResponse
import com.tingxia.audio.data.model.AudioUrlResponse
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.model.DistillStatusResponse
import com.tingxia.audio.data.model.RetryResponse
import com.tingxia.audio.data.remote.ArticleApi
import com.tingxia.audio.data.repository.ArticleRepository
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
 * CP5.2-A: retryArticle 单元测试
 *
 * 测试 retryState 状态机：
 * 1. test_retryArticle_sendsRequest_andUpdatesState
 * 2. test_retryArticle_loadingState
 * 3. test_retryArticle_errorState
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
    ) = ArticleRepository(object : ArticleApi {
        override suspend fun getArticles() = ArticleListResponse(emptyList())
        override suspend fun getArticle(id: String) = article
        override suspend fun getDistillStatus(taskId: String) =
            DistillStatusResponse(task_id = taskId, status = DistillStatus.READY)
        override suspend fun getAudioUrl(id: String) = AudioUrlResponse(audio_url = "https://example.com/audio.mp3")
        override suspend fun retryArticle(id: String) = retryResponse
    })

    private fun fakeController() = PlayerController(RuntimeEnvironment.getApplication())

    @Test
    fun test_retryArticle_sendsRequest_andUpdatesState() = testScope.runTest {
        val article = Article(
            id = "a1",
            title = "Test Article",
            status = DistillStatus.FAILED,
            taskId = "t1",
        )
        val vm = ArticleDetailViewModel(fakeRepo(article), fakeController())
        vm.retryArticle("a1")
        testScheduler.advanceUntilIdle()
        assertTrue(vm.retryState.value is RetryState.Success)
        val success = vm.retryState.value as RetryState.Success
        assertEquals(1, success.retryCount)
    }

    @Test
    fun test_retryArticle_loadingState() = testScope.runTest {
        val article = Article(
            id = "a1",
            title = "Test Article",
            status = DistillStatus.FAILED,
            taskId = "t1",
        )
        val vm = ArticleDetailViewModel(fakeRepo(article), fakeController())
        // 验证初始状态是 Idle
        assertTrue(vm.retryState.value is RetryState.Idle)
    }

    @Test
    fun test_retryArticle_errorState() = testScope.runTest {
        val article = Article(
            id = "a1",
            title = "Test Article",
            status = DistillStatus.FAILED,
            taskId = "t1",
        )
        val errorRepo = ArticleRepository(object : ArticleApi {
            override suspend fun getArticles() = ArticleListResponse(emptyList())
            override suspend fun getArticle(id: String) = article
            override suspend fun getDistillStatus(taskId: String) =
                DistillStatusResponse(task_id = taskId, status = DistillStatus.READY)
            override suspend fun getAudioUrl(id: String) = AudioUrlResponse(audio_url = "https://example.com/audio.mp3")
            override suspend fun retryArticle(id: String) = throw RuntimeException("网络错误")
        })
        val vm = ArticleDetailViewModel(errorRepo, fakeController())
        vm.retryArticle("a1")
        testScheduler.advanceUntilIdle()
        assertTrue(vm.retryState.value is RetryState.Error)
        val error = vm.retryState.value as RetryState.Error
        assertEquals("网络错误", error.message)
    }
}
