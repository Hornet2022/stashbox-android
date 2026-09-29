package com.tingxia.audio.ui.articles

import com.tingxia.audio.data.FakeArticleApi
import com.tingxia.audio.data.model.Article
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

    private fun fakeRepo(articles: List<Article>) = ArticleRepository(
        FakeArticleApi().apply { this.articles = articles },
    )

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

    @Test
    fun deleteArticle_removesFromList() = testScope.runTest {
        val sample = listOf(
            Article(id = "1", title = "A"),
            Article(id = "2", title = "B"),
        )
        val vm = ArticleListViewModel(fakeRepo(sample))
        vm.loadArticles()
        testScheduler.advanceUntilIdle()
        vm.deleteArticle("1")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(sample[1]), vm.articles.value)
    }
}