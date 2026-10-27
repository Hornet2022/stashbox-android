package com.tingxia.audio.ui.articles

import com.tingxia.audio.data.FakeArticleApi
import com.tingxia.audio.data.FakeTagApi
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.ShareResponse
import com.tingxia.audio.data.model.Tag
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.data.repository.TagRepository
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

    private fun fakeRepo(articles: List<Article>, api: FakeArticleApi = FakeArticleApi()) = api.apply {
        this.articles = articles
    }.let { ArticleRepository(it) }

    private fun fakeVm(
        articles: List<Article> = emptyList(),
        articleApi: FakeArticleApi = FakeArticleApi(),
        tagApi: FakeTagApi = FakeTagApi(),
    ) = ArticleListViewModel(
        repository = fakeRepo(articles, articleApi),
        tagRepository = TagRepository(tagApi),
    )

    @Test
    fun initialState_isEmpty() = testScope.runTest {
        val vm = fakeVm(emptyList())
        assertEquals(emptyList<Article>(), vm.articles.value)
        assertEquals(false, vm.isLoading.value)
    }

    @Test
    fun loadArticles_emitsArticleList() = testScope.runTest {
        val sample = listOf(
            Article(id = "1", title = "文章A"),
            Article(id = "2", title = "文章B"),
        )
        val vm = fakeVm(sample)
        vm.loadArticles()
        testScheduler.advanceUntilIdle()
        assertEquals(sample, vm.articles.value)
    }

    @Test
    fun loadArticles_resetsLoadingAfterComplete() = testScope.runTest {
        val vm = fakeVm(emptyList())
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
        val vm = fakeVm(sample)
        vm.loadArticles()
        testScheduler.advanceUntilIdle()
        vm.deleteArticle("1")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(sample[1]), vm.articles.value)
    }

    // ─────────────── 0041 共享（列表页独立一份） ───────────────

    @Test
    fun loadAvailableTags_populatesFromTagApi() = testScope.runTest {
        val tagApi = FakeTagApi().apply {
            tags = listOf(
                Tag(id = "t1", name = "科技", category = "interest"),
                Tag(id = "t2", name = "财经", category = "interest"),
            )
        }
        val vm = fakeVm(tagApi = tagApi)
        vm.loadAvailableTags()
        testScheduler.advanceUntilIdle()
        assertEquals(2, vm.availableTags.value.size)
        assertEquals("科技", vm.availableTags.value[0].name)
    }

    @Test
    fun loadAvailableTags_isLazyAndLoadedOnce() = testScope.runTest {
        val tagApi = FakeTagApi().apply { tags = listOf(Tag(id = "t1", name = "科技", category = "interest")) }
        val vm = fakeVm(tagApi = tagApi)
        // 不调 loadAvailableTags 前为空。
        assertEquals(emptyList<Tag>(), vm.availableTags.value)
        vm.loadAvailableTags()
        testScheduler.advanceUntilIdle()
        // 二次调用（force=false）不再重拉，列表保持。
        tagApi.tags = emptyList()
        vm.loadAvailableTags()
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.availableTags.value.size)
    }

    @Test
    fun loadAvailableTags_failureKeepsEmptyAndDoesNotBlock() = testScope.runTest {
        val tagApi = FakeTagApi().apply { tags = listOf(Tag(id = "t1", name = "科技", category = "interest")) }
        // 让 listTags 抛错：用抛错的替身模拟失败。
        val failing = object : com.tingxia.audio.data.remote.TagApi {
            override suspend fun listTags(): com.tingxia.audio.data.model.TagsResponse =
                throw RuntimeException("boom")
            override suspend fun subscribeTag(tagIdOrSlug: String): com.tingxia.audio.data.model.SubscribeResponse =
                com.tingxia.audio.data.model.SubscribeResponse(ok = true)
            override suspend fun unsubscribeTag(tagIdOrSlug: String): com.tingxia.audio.data.model.SubscribeResponse =
                com.tingxia.audio.data.model.SubscribeResponse(ok = true)
        }
        val vm = ArticleListViewModel(
            repository = fakeRepo(emptyList()),
            tagRepository = TagRepository(failing),
        )
        vm.loadAvailableTags()
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList<Tag>(), vm.availableTags.value)
    }

    @Test
    fun toggleTag_addsAndRemovesSelection() = testScope.runTest {
        val vm = fakeVm()
        vm.toggleTag("t1")
        assertEquals(setOf("t1"), vm.selectedTags.value)
        vm.toggleTag("t1")
        assertEquals(emptySet<String>(), vm.selectedTags.value)
    }

    @Test
    fun presetAndClearSelectedTags() = testScope.runTest {
        val vm = fakeVm()
        vm.presetSelectedTags(setOf("a", "b"))
        assertEquals(setOf("a", "b"), vm.selectedTags.value)
        vm.clearSelectedTags()
        assertEquals(emptySet<String>(), vm.selectedTags.value)
    }

    @Test
    fun shareArticle_successWritesBackToListAndSetsState() = testScope.runTest {
        val articleApi = FakeArticleApi().apply {
            shareResponse = ShareResponse(id = "1", shared = true, sharedTags = listOf("科技"))
        }
        val sample = listOf(Article(id = "1", title = "A"))
        val vm = fakeVm(articles = sample, articleApi = articleApi)
        vm.loadArticles()
        testScheduler.advanceUntilIdle()

        vm.shareArticle("1", listOf("科技"))
        testScheduler.advanceUntilIdle()

        // 列表里那篇的徽标状态被写回。
        assertEquals(true, vm.articles.value.first().shared)
        assertEquals(listOf("科技"), vm.articles.value.first().sharedTags)
        assertEquals(true, (vm.shareState.value as ShareState.Success).shared)
    }

    @Test
    fun shareArticle_failure_setsErrorState() = testScope.runTest {
        val articleApi = FakeArticleApi().apply { shareError = RuntimeException("网络错误") }
        val vm = fakeVm(articles = listOf(Article(id = "1", title = "A")), articleApi = articleApi)
        vm.shareArticle("1", listOf("科技"))
        testScheduler.advanceUntilIdle()
        assertTrue(vm.shareState.value is ShareState.Error)
    }

    @Test
    fun unshareArticle_writesBackUnshared() = testScope.runTest {
        val sample = listOf(Article(id = "1", title = "A", shared = true, sharedTags = listOf("科技")))
        val vm = fakeVm(articles = sample)
        vm.loadArticles()
        testScheduler.advanceUntilIdle()

        vm.unshareArticle("1")
        testScheduler.advanceUntilIdle()

        assertEquals(false, vm.articles.value.first().shared)
        assertEquals(false, (vm.shareState.value as ShareState.Success).shared)
    }

    @Test
    fun dismissShareError_clearsErrorToIdle() = testScope.runTest {
        val articleApi = FakeArticleApi().apply { shareError = RuntimeException("boom") }
        val vm = fakeVm(articleApi = articleApi)
        vm.shareArticle("1", emptyList())
        testScheduler.advanceUntilIdle()
        assertTrue(vm.shareState.value is ShareState.Error)
        vm.dismissShareError()
        assertEquals(ShareState.Idle, vm.shareState.value)
    }
}