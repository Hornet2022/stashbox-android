package com.tingxia.audio.integration

import com.tingxia.audio.data.remote.FavoritesApi
import com.tingxia.audio.data.remote.AddFavoriteRequest
import com.tingxia.audio.data.remote.UpdateFavoriteRequest
import com.tingxia.audio.data.remote.SnoozeRequest
import com.tingxia.audio.data.repository.FavoritesRepository
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
 * CP5.5-B1 收藏 + 稍后听 E2E 测试：真 MockWebServer + 真 Retrofit 链路。
 *
 * 不起 emulator，不起 dev 服务，只在 JVM 跑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesTest {

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

    private fun makeApi(): FavoritesApi {
        val ok = OkHttpClient.Builder().build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(ok)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(FavoritesApi::class.java)
    }

    @Test
    fun listFavorites_returnsThreeFavorites() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"favorites":[{"id":1,"article_id":"art_1","folder":"tech","note":"笔记1","created_at":"2026-09-18T10:00:00Z"},{"id":2,"article_id":"art_2","folder":"default","note":null,"created_at":"2026-09-18T11:00:00Z"},{"id":3,"article_id":"art_3","folder":"金句","note":"好句","created_at":"2026-09-18T12:00:00Z"}]}""")
        )
        val api = makeApi()
        val response = api.listFavorites()
        assertEquals(3, response.favorites.size)
        assertEquals("art_1", response.favorites[0].article_id)
        assertEquals("tech", response.favorites[0].folder)
    }

    @Test
    fun listFavorites_withFolderFilter_returnsOne() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"favorites":[{"id":1,"article_id":"art_1","folder":"tech","note":"笔记1","created_at":"2026-09-18T10:00:00Z"}]}""")
        )
        val api = makeApi()
        val response = api.listFavorites("tech")
        assertEquals(1, response.favorites.size)
        assertEquals("tech", response.favorites[0].folder)
    }

    @Test
    fun listFolders_returnsThreeFolders() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"folders":[{"folder":"default","count":5},{"folder":"tech","count":12},{"folder":"金句","count":3}]}""")
        )
        val api = makeApi()
        val response = api.listFolders()
        assertEquals(3, response.folders.size)
        assertEquals("default", response.folders[0].folder)
        assertEquals(5, response.folders[0].count)
    }

    @Test
    fun addFavorite_returnsOkWithId() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"id":1,"folder":"tech"}""")
        )
        val api = makeApi()
        val response = api.addFavorite("art_xxx", AddFavoriteRequest("tech", "很好的文章"))
        assertTrue(response.ok)
        assertEquals(1, response.id)
        assertEquals("tech", response.folder)
    }

    @Test
    fun updateFavorite_returnsOkWithId() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"id":1}""")
        )
        val api = makeApi()
        val response = api.updateFavorite(1, UpdateFavoriteRequest("tech", "新笔记"))
        assertTrue(response.ok)
        assertEquals(1, response.id)
    }

    @Test
    fun deleteFavorite_returnsOkTrue() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}""")
        )
        val api = makeApi()
        val response = api.deleteFavorite(1)
        assertTrue(response["ok"] ?: false)
    }

    @Test
    fun listLaterListens_returnsTwoItems() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"later_listens":[{"id":1,"article_id":"art_yyy","snooze_until":"2026-09-19T20:00:00Z","created_at":"2026-09-18T10:00:00Z"},{"id":2,"article_id":"art_zzz","snooze_until":null,"created_at":"2026-09-18T11:00:00Z"}]}""")
        )
        val api = makeApi()
        val response = api.listLaterListens()
        assertEquals(2, response.later_listens.size)
        assertEquals("art_yyy", response.later_listens[0].article_id)
    }

    @Test
    fun snooze_returnsOkWithId() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"id":2}""")
        )
        val api = makeApi()
        val response = api.snooze("art_yyy", SnoozeRequest("2026-09-19T20:00:00Z"))
        assertTrue(response.ok)
        assertEquals(2, response.id)
    }

    @Test
    fun unsnooze_returnsOkTrue() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}""")
        )
        val api = makeApi()
        val response = api.unsnooze("art_yyy")
        assertTrue(response["ok"] ?: false)
    }

    @Test
    fun repository_deleteFavorite_returnsTrue() = testScope.runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}""")
        )
        val api = makeApi()
        val repo = FavoritesRepository(api)
        val result = repo.deleteFavorite(1)
        assertTrue(result)
    }
}
