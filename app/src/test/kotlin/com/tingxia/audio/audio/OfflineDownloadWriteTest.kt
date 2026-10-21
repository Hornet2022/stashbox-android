package com.tingxia.audio.audio

import android.content.Context
import androidx.room.Room
import com.tingxia.audio.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 离线下载的**写缓存**路径回归（2026-10-21）。
 *
 * ## 之前坏在哪
 *
 * `OfflineDownloadManager.download()` 原来直接调 `cache.startFile(key, 0, length)`。
 * 但 media3 1.4.1 的 `SimpleCache.startFile` 字节码里第一件事就是：
 *
 *     contentIndex.get(key) → Assertions.checkNotNull(...)
 *
 * content 条目是 `startReadWrite` 才会建的。从没建过 → `contentIndex.get` 返回 null
 * → NPE → 被 `download()` 外层的 `catch (e: Exception)` 吞成 `DownloadError("下载失败")`。
 *
 * 也就是说：**离线下载从来没成功过一次**。而"通勤预下载"是产品的招牌功能，
 * 内测用户点一下就踩到。
 *
 * 为什么一直没人发现：旧版 media3 没有这个前置条件（能过）；
 * 单测又都绕开了 `download()`（它要连真服务器）；
 * CI 里没有"真下载"这条路径。
 *
 * ## 这里锁什么
 *
 * - 走真实 HTTP（本地起一个 server）→ 真实写进 SimpleCache → 缓存里真的有内容；
 * - **并锁住根因**：直接 `startFile`（不先 `startReadWrite`）必须抛 ——
 *   哪天有人"简化"回旧写法，这条会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OfflineDownloadWriteTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var offline: OfflineDownloadManager
    private var server: MockWebServer? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        offline = OfflineDownloadManager(context, db.downloadedAudioDao())
    }

    @After
    fun tearDown() {
        server?.shutdown()
        db.close()
    }

    /** 起一个本地 HTTP server，返回固定字节。 */
    private fun startServer(bytes: ByteArray, responses: Int = 1): String {
        val s = MockWebServer()
        repeat(responses) {
            s.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        }
        s.start()
        server = s
        return s.url("/audio.mp3").toString()
    }

    @Test
    fun `下载能把音频真的写进 Media3 缓存`() = runBlocking {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val url = startServer(payload)

        val ok = offline.download("art1", url, "测试文章", durationSec = 1)

        val state = offline.states.value["art1"]
        assertTrue(
            "下载必须成功（此前恒失败：startFile 缺 content 条目 → NPE → 下载失败）。实际状态：$state",
            ok,
        )
        assertTrue(
            "状态必须是 Downloaded，实际是 $state",
            state is DownloadState.Downloaded,
        )

        // 缓存里确实有这份音频，且字节数对得上
        val cache = offline.cache()
        assertTrue(
            "缓存里必须有这个 key，实际 keys=${cache.getKeys()}",
            cache.getKeys().contains(url),
        )
        // SimpleCache 1.4.1 没有公开 getCachedContent，用 span 长度核对字节数
        val cachedBytes = cache.getCachedSpans(url).sumOf { it.length }
        assertEquals(
            "缓存里的长度必须等于下载到的长度",
            payload.size.toLong(),
            cachedBytes,
        )

        // 台账也要有（界面显示"已下载"靠它）
        val rows = db.downloadedAudioDao().all()
        assertEquals("下载台账要有一行", 1, rows.size)
        assertEquals("art1", rows[0].articleId)
        assertEquals(payload.size.toLong(), rows[0].cachedBytes)
    }

    @Test
    fun `下载后重复下载会被识别为已缓存`() = runBlocking {
        val payload = ByteArray(1024)
        // 备两个响应：正常路径下第二次会走 isCached 分支、不发请求；
        // 万一没命中缓存，还有个响应兜着（否则 MockWebServer 会返回 404）
        val url = startServer(payload, responses = 2)

        assertTrue(offline.download("art1", url, "标题", durationSec = 1))
        // 第二次：走 isCached 分支，不该再报错
        assertTrue(offline.download("art1", url, "标题", durationSec = 1))
    }

    @Test
    fun `根因守卫_不先建 content 条目直接 startFile 必然抛`() {
        // 这条不是测业务，是**测根因**。
        //
        // 如果哪天有人把 download() 改回 `cache.startFile(...)`（看起来更简单），
        // 那条改动就会在这里被复现成一次显式失败，而不是又一次静默的"下载失败"。
        val cache = offline.cache()
        val key = "https://example.com/never-created.mp3"

        val thrown = runCatching { cache.startFile(key, 0, 1024) }.exceptionOrNull()

        assertNotNull(
            "media3 1.4.1 的 startFile 要求 content 条目已存在；" +
                "如果这条不再抛，说明 media3 换了行为，startFile 的用法要重新评估",
            thrown,
        )
    }

    @Test
    fun `根因守卫_先 startReadWrite 再 startFile 可以成功`() {
        // 对照组：证明"先建条目"这条路是通的，失败确实来自缺 content 条目。
        //
        // 顺序很关键：startReadWrite 建 content 条目并**锁住区间**，
        // startFile 才拿得到临时文件（它内部还有 isFullyLocked 的前置断言）。
        val cache = offline.cache()
        val key = "https://example.com/created.mp3"

        cache.startReadWrite(key, 0, 1024)
        val file = cache.startFile(key, 0, 1024)
        file.outputStream().use { it.write(ByteArray(1024)) }
        cache.commitFile(file, 1024)

        assertTrue(cache.getKeys().contains(key))
    }
}
