package com.tingxia.audio.integration

import com.tingxia.audio.data.remote.TagApi
import com.tingxia.audio.data.repository.TagRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * CP5.3-C 标签订阅 E2E 测试：真 MockWebServer + 真 Retrofit 链路。
 *
 * 不起 emulator，不起 dev 服务，只在 JVM 跑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagSubscriptionTest {

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

    private fun makeApi(): TagApi {
        val ok = OkHttpClient.Builder().build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(ok)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(TagApi::class.java)
    }

    @Test
    fun listTags_returnsTagsFromServer() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"tags":[{"id":"tech","name":"科技","category":"system"},{"id":"history","name":"历史","category":"system"},{"id":"science","name":"科学","category":"system"}]}""")
        )
        val api = makeApi()
        val response = api.listTags()
        assertEquals(3, response.tags.size)
        assertEquals("科技", response.tags[0].name)
        assertEquals("system", response.tags[0].category)
    }

    @Test
    fun subscribeTag_returnsOkTrue() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"already_subscribed":false}""")
        )
        val api = makeApi()
        val response = api.subscribeTag("tech")
        assertTrue(response.ok)
    }

    @Test
    fun unsubscribeTag_returnsOkTrue() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"was_subscribed":true}""")
        )
        val api = makeApi()
        val response = api.unsubscribeTag("tech")
        assertTrue(response.ok)
    }

    @Test
    fun repository_listTags_pullsFromRealEndpoint() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"tags":[{"id":"tech","name":"科技","category":"system"}]}""")
        )
        val api = makeApi()
        val repo = TagRepository(api)
        val tags = repo.listTags()
        assertEquals(1, tags.size)
        assertEquals("tech", tags[0].id)
    }

    @Test
    fun repository_subscribe_returnsTrue() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true}""")
        )
        val api = makeApi()
        val repo = TagRepository(api)
        val result = repo.subscribe("tech")
        assertTrue(result)
    }

    @Test
    fun repository_unsubscribe_returnsTrue() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true}""")
        )
        val api = makeApi()
        val repo = TagRepository(api)
        val result = repo.unsubscribe("tech")
        assertTrue(result)
    }
}
