package com.tingxia.audio.ui.articles

import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.model.ArticleListResponse
import com.tingxia.audio.data.model.AudioUrlResponse
import com.tingxia.audio.data.model.DistillStatusResponse
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleListViewModelTest {

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

    private fun fakeRepo(articles: List<Article>) = ArticleRepository(object : ArticleApi {
        override suspend fun getArticles() = ArticleListResponse(articles)
        override suspend fun getArticle(id: String) = articles.first { it.id == id }
        override suspend fun getDistillStatus(taskId: String) =
            DistillStatusResponse(task_id = taskId, status = DistillStatus.READY)
        override suspend fun getAudioUrl(id: String) =
            AudioUrlResponse(audio_url = "https://example.com/$id.mp3")
    })

    @Test
    fun initialState_isEmpty() = testScope.runTest {
        val vm = ArticleListViewModel(fakeRepo(emptyList()))
        assertEquals(emptyList<Article>(), vm.articles.value)
        assertEquals(false, vm.isLoading.value)
    }

    @Test
    fun loadArticles_emitsArticleList() = testScope.runTest {
        val sample = listOf(
            Article(id = "1", title = "文章A"),
            Article(id = "2", title = "文章B"),
        )
        val vm = ArticleListViewModel(fakeRepo(sample))
        vm.loadArticles()
        testScheduler.advanceUntilIdle()
        assertEquals(sample, vm.articles.value)
    }

    @Test
    fun loadArticles_resetsLoadingAfterComplete() = testScope.runTest {
        val vm = ArticleListViewModel(fakeRepo(emptyList()))
        vm.loadArticles()
        testScheduler.advanceUntilIdle()
        assertEquals(false, vm.isLoading.value)
    }
}
