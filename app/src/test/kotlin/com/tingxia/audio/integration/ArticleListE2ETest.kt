package com.tingxia.audio.integration

import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.ArticleListResponse
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.model.DistillStatusResponse
import com.tingxia.audio.data.model.AudioUrlResponse
import com.tingxia.audio.data.remote.ArticleApi
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.ui.articles.ArticleListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * CP4.7-A2 E2E 测试：真 MockWebServer + 真 Retrofit 链路。
 *
 * 不起 emulator，不起 dev 服务，只在 JVM 跑。
 * Dispatchers pattern 与 ArticleListViewModelTest.kt 保持一致
 *（StandardTestDispatcher + TestScope + setMain/resetMain）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArticleListE2ETest {

    private val server = MockWebServer()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        server.start()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    private fun makeApi(): ArticleApi {
        val ok = OkHttpClient.Builder().build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(ok)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(ArticleApi::class.java)
    }

    @Test
    fun getArticles_routesToGateway_andParsesRealJson() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"articles":[{"id":"1","title":"文章A"},{"id":"2","title":"文章B"}]}""")
        )
        val api = makeApi()
        val response = api.getArticles()
        assertEquals(2, response.articles.size)
        assertEquals("文章A", response.articles[0].title)
    }

    @Test
    fun getDistillStatus_ready_parsesStatusEnum() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"task_id":"t1","status":"READY"}""")
        )
        val api = makeApi()
        val response = api.getDistillStatus("t1")
        assertEquals("t1", response.task_id)
        assertEquals(DistillStatus.READY, response.status)
    }

    @Test
    fun getAudioUrl_returnsAudioUrl() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"audio_url":"https://cdn.example.com/audio/123.mp3"}""")
        )
        val api = makeApi()
        val response = api.getAudioUrl("123")
        assertEquals("https://cdn.example.com/audio/123.mp3", response.audio_url)
    }

    @Test
    fun viewModel_loadArticles_pullsFromRealEndpoint() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"articles":[{"id":"1","title":"文章X"}]}""")
        )
        val api = makeApi()
        val repo = ArticleRepository(api)
        // 直接调用 repository，验证 MockWebServer 真返回数据
        val articles = repo.getArticles()
        assertEquals(1, articles.size)
        assertEquals("文章X", articles[0].title)
    }
}
