package com.tingxia.audio.integration

import com.tingxia.audio.data.remote.DeviceInfo
import com.tingxia.audio.data.remote.FeedbackApi
import com.tingxia.audio.data.remote.FeedbackCategory
import com.tingxia.audio.data.remote.FeedbackRequest
import com.tingxia.audio.data.repository.FeedbackRepository
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
 * CP5.5-A3 反馈功能 E2E 测试：真 MockWebServer + 真 Retrofit 链路。
 *
 * 不起 emulator，不起 dev 服务，只在 JVM 跑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackTest {

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

    private fun makeApi(): FeedbackApi {
        val ok = OkHttpClient.Builder().build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(ok)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(FeedbackApi::class.java)
    }

    @Test
    fun submitFeedback_withFullBody_returnsOkWithId() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"id":1,"category":"bug"}""")
        )
        val api = makeApi()
        val response = api.submitFeedback(
            FeedbackRequest(
                article_id = "art_xxx",
                category = "bug",
                rating = 4,
                content = "蒸馏时长太长",
                contact = "wechat:xxx",
                device_info = DeviceInfo("1.0.0", "Android 14", "Pixel 8"),
            )
        )
        assertTrue(response.ok)
        assertEquals(1, response.id)
        assertEquals("bug", response.category)
    }

    @Test
    fun submitFeedback_withMinimalBody_returnsOk() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"id":2,"category":"feature"}""")
        )
        val api = makeApi()
        val response = api.submitFeedback(
            FeedbackRequest(
                category = "feature",
                content = "希望支持批量下载",
            )
        )
        assertTrue(response.ok)
        assertEquals(2, response.id)
    }

    @Test
    fun submitFeedback_withInvalidCategory_returnsError() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"invalid category"}""")
        )
        val api = makeApi()
        var exceptionCaught = false
        try {
            api.submitFeedback(
                FeedbackRequest(
                    category = "invalid_category",
                    content = "test content",
                )
            )
        } catch (_: Exception) {
            exceptionCaught = true
        }
        assertTrue(exceptionCaught)
    }

    @Test
    fun submitFeedback_withEmptyContent_returnsError() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"content is required"}""")
        )
        val api = makeApi()
        var exceptionCaught = false
        try {
            api.submitFeedback(
                FeedbackRequest(
                    category = "bug",
                    content = "",
                )
            )
        } catch (_: Exception) {
            exceptionCaught = true
        }
        assertTrue(exceptionCaught)
    }

    @Test
    fun listFeedbacks_returnsTwoItems() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"feedbacks":[{"id":1,"article_id":"art_1","category":"bug","rating":4,"content":"内容1","created_at":"2026-09-18T10:00:00Z"},{"id":2,"article_id":null,"category":"feature","rating":null,"content":"内容2","created_at":"2026-09-18T11:00:00Z"}]}""")
        )
        val api = makeApi()
        val response = api.listFeedbacks()
        assertEquals(2, response.feedbacks.size)
        assertEquals("art_1", response.feedbacks[0].article_id)
        assertEquals(4, response.feedbacks[0].rating)
    }

    @Test
    fun repository_submit_withBugAndContent_returnsSuccess() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"id":1,"category":"bug"}""")
        )
        val api = makeApi()
        val repo = FeedbackRepository(api)
        val result = repo.submit(
            category = FeedbackCategory.BUG,
            content = "蒸馏时长太长",
        )
        assertTrue(result.ok)
        assertEquals(1, result.id)
    }

    @Test
    fun repository_submit_withBugRatingContact_returnsSuccess() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"id":2,"category":"bug"}""")
        )
        val api = makeApi()
        val repo = FeedbackRepository(api)
        val result = repo.submit(
            category = FeedbackCategory.BUG,
            content = "音频质量不好",
            rating = 2,
            contact = "wechat:zhangsan",
        )
        assertTrue(result.ok)
        assertEquals(2, result.id)
    }

    @Test
    fun repository_list_returnsTwoFeedbacks() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"feedbacks":[{"id":1,"article_id":"art_1","category":"bug","rating":4,"content":"内容1","created_at":"2026-09-18T10:00:00Z"},{"id":2,"article_id":"art_2","category":"feature","rating":null,"content":"内容2","created_at":"2026-09-18T11:00:00Z"}]}""")
        )
        val api = makeApi()
        val repo = FeedbackRepository(api)
        val result = repo.list()
        assertEquals(2, result.size)
    }
}
