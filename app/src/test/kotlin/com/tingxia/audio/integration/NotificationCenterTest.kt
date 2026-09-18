package com.tingxia.audio.integration

import com.tingxia.audio.data.remote.MarkReadResponse
import com.tingxia.audio.data.remote.Notification
import com.tingxia.audio.data.remote.NotificationApi
import com.tingxia.audio.data.remote.NotificationsResponse
import com.tingxia.audio.data.repository.NotificationRepository
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
 * CP5.4-C 推送通知中心集成测试：真 MockWebServer + 真 Retrofit 链路。
 *
 * 不起 emulator，不起 dev 服务，只在 JVM 跑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationCenterTest {

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

    private fun makeApi(): NotificationApi {
        val ok = OkHttpClient.Builder().build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(ok)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(NotificationApi::class.java)
    }

    @Test
    fun listNotifications_returnsTwoNotifications() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "notifications": [
                            {
                                "id": 123,
                                "article_id": "uuid-abc",
                                "tag_slug": "tech",
                                "title": "新文章已蒸馏完成",
                                "body": "OpenAI 发布 GPT-5...",
                                "deeplink": "stashbox://article/uuid-abc",
                                "read": false,
                                "created_at": "2026-09-18T14:00:00Z"
                            },
                            {
                                "id": 124,
                                "article_id": "uuid-def",
                                "tag_slug": "science",
                                "title": "科学新发现",
                                "body": "NASA 宣布...",
                                "deeplink": "stashbox://article/uuid-def",
                                "read": true,
                                "created_at": "2026-09-17T10:00:00Z"
                            }
                        ]
                    }
                """.trimIndent())
        )
        val api = makeApi()
        val response = api.listNotifications()
        assertEquals(2, response.notifications.size)
        assertEquals("新文章已蒸馏完成", response.notifications[0].title)
        assertEquals(false, response.notifications[0].read)
        assertEquals("tech", response.notifications[0].tag_slug)
    }

    @Test
    fun listNotifications_unreadOnly_returnsOneUnread() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "notifications": [
                            {
                                "id": 123,
                                "article_id": "uuid-abc",
                                "tag_slug": "tech",
                                "title": "新文章已蒸馏完成",
                                "body": "OpenAI 发布 GPT-5...",
                                "deeplink": "stashbox://article/uuid-abc",
                                "read": false,
                                "created_at": "2026-09-18T14:00:00Z"
                            }
                        ]
                    }
                """.trimIndent())
        )
        val api = makeApi()
        val response = api.listNotifications(unreadOnly = true)
        assertEquals(1, response.notifications.size)
        assertEquals(false, response.notifications[0].read)
    }

    @Test
    fun markRead_returnsOkTrue() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}""")
        )
        val api = makeApi()
        val response = api.markRead(123)
        assertTrue(response.ok)
    }

    @Test
    fun repository_listNotifications_returnsTwoNotifications() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "notifications": [
                            {
                                "id": 123,
                                "article_id": "uuid-abc",
                                "tag_slug": "tech",
                                "title": "新文章已蒸馏完成",
                                "body": "OpenAI 发布 GPT-5...",
                                "deeplink": "stashbox://article/uuid-abc",
                                "read": false,
                                "created_at": "2026-09-18T14:00:00Z"
                            },
                            {
                                "id": 124,
                                "article_id": "uuid-def",
                                "tag_slug": "science",
                                "title": "科学新发现",
                                "body": "NASA 宣布...",
                                "deeplink": "stashbox://article/uuid-def",
                                "read": true,
                                "created_at": "2026-09-17T10:00:00Z"
                            }
                        ]
                    }
                """.trimIndent())
        )
        val api = makeApi()
        val repo = NotificationRepository(api)
        val notifications = repo.list()
        assertEquals(2, notifications.size)
        assertEquals("tech", notifications[0].tag_slug)
    }

    @Test
    fun repository_listNotifications_unreadOnly_returnsOneUnread() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "notifications": [
                            {
                                "id": 123,
                                "article_id": "uuid-abc",
                                "tag_slug": "tech",
                                "title": "新文章已蒸馏完成",
                                "body": "OpenAI 发布 GPT-5...",
                                "deeplink": "stashbox://article/uuid-abc",
                                "read": false,
                                "created_at": "2026-09-18T14:00:00Z"
                            }
                        ]
                    }
                """.trimIndent())
        )
        val api = makeApi()
        val repo = NotificationRepository(api)
        val notifications = repo.list(unreadOnly = true)
        assertEquals(1, notifications.size)
        assertEquals(false, notifications[0].read)
    }

    @Test
    fun repository_markRead_returnsTrue() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}""")
        )
        val api = makeApi()
        val repo = NotificationRepository(api)
        val result = repo.markRead(123)
        assertTrue(result)
    }
}
