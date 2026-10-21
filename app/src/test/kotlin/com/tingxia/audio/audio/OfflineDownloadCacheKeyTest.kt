package com.tingxia.audio.audio

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.room.Room
import com.tingxia.audio.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 离线下载与播放器**必须共用同一个缓存条目**（2026-10-21 真机 E2E 回归）。
 *
 * ## 之前坏在哪
 *
 * 真机上点「下载」永远失败，而且**日志里什么都没有** —— 异常是 NPE，
 * message 为 null（`Assertions.checkNotNull` 抛的），UI 只显示「下载失败」。
 *
 * 根因是 **key 和文件格式**这两件事：
 *
 * 1. **文件格式不兼容**
 *    media3 1.4.1 内部把 key **哈希**成 `<hash>.uid` 存盘，
 *    **没法从文件名反解出 key**。而 legacy 的
 *    `SimpleCache.startFile` / `commitFile` 恰恰是靠**解析文件名**拿 key 的
 *    （`SimpleCacheSpan.createCacheEntry`）—— 面对哈希名只能返回 null →
 *    `Assertions.checkNotNull` → NPE。
 *    v3 路径（`CacheDataSource` / `CacheWriter`）走 `CacheFileMetadataIndex`
 *    这张真实索引表，不依赖文件名，所以能正常工作。
 *
 * 2. **key 口径不一致**
 *    播放器走 `CacheDataSource`，key 由 `CacheKeyFactory.DEFAULT` 决定
 *    （= `dataSpec.key ?: uri.toString()`）。下载路径原来自己另算一套，
 *    于是即使写成功，写的也是**另一份**缓存，播放器读不到。
 *
 * 现在下载改用 media3 官方的 `CacheWriter`，并**复用播放器那个工厂**
 * （`buildCacheDataSource`）—— key、flags、文件格式全部同源。
 *
 * ## 这里锁什么
 *
 * - 下载用的 key 与播放器（`CacheKeyFactory.DEFAULT`）**完全一致**；
 * - 下载完成后，**播放器那条链路能从缓存里读到**这份音频，
 *   且上游一个字节都不请求 —— 这条才是"下载"这个功能的真正目的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OfflineDownloadCacheKeyTest {

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

    private fun startServer(bytes: ByteArray): String {
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        s.start()
        server = s
        return s.url("/audio.mp3").toString()
    }

    /** 上游的 open() 一被调用就炸 —— 用来证明"读的时候没发网络请求"。 */
    private class TrackingSource : DataSource {
        var opened = false
        override fun open(dataSpec: DataSpec): Long {
            opened = true
            throw java.io.IOException("不该发网络请求：说明播放器没命中缓存")
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun close() {}
        override fun getUri(): Uri? = null
        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}
    }

    @Test
    fun `下载用的 key 和播放器 CacheKeyFactory DEFAULT 完全一致`() {
        val url = "http://10.0.2.2:8100/api/v1/x.wav"
        val playerKey = CacheKeyFactory.DEFAULT.buildCacheKey(DataSpec(Uri.parse(url)))
        assertEquals(
            "下载和播放器必须写同一个缓存条目，否则下载了播放器也读不到",
            playerKey,
            OfflineDownloadManager.cacheKey(url),
        )
    }

    @Test
    fun `下载后播放器能从缓存里读到，上游一个字节都不请求`() = runBlocking {
        val payload = ByteArray(48 * 1024) { (it % 251).toByte() }
        val url = startServer(payload)

        // 先下载
        assertTrue("下载应当成功", offline.download("art1", url, "标题", durationSec = 1))

        // 再用播放器那条链路去读同一个 URL，
        // 上游故意做成"一被 open() 就炸" —— 能读到就说明走的是缓存。
        //
        // ⚠️ 标记要打在 **DataSource.open()** 上，不能打在工厂上：
        // media3 的 `CacheDataSource.Factory.createDataSource()` 会**提前**创建上游
        // DataSource（工厂 lambda 一定会被调），只有 open() 才代表"真的发请求了"。
        val exploding = TrackingSource()
        val cacheDataSource = offline.buildCacheDataSource(DataSource.Factory { exploding })

        // CacheDataSource.open() 返回的是**位置(long)**，不是 DataSource
        cacheDataSource.open(DataSpec(Uri.parse(url)))
        val buf = ByteArray(payload.size)
        var total = 0
        while (total < buf.size) {
            val n = cacheDataSource.read(buf, total, buf.size - total)
            if (n <= 0) break
            total += n
        }
        cacheDataSource.close()

        assertTrue("上游真的被请求了，说明下载的音频没进播放器读的那个缓存条目", !exploding.opened)
        assertEquals("从缓存读到的长度不对", payload.size, total)
        assertArrayEquals("从缓存读到的内容不对", payload, buf)
    }

    @Test
    fun `播放器边播边写留下的半截缓存不能算已下载`() = runBlocking {
        // 2026-10-21 真机 E2E 抓到：点"下载到本地"后按钮立刻变成"已下载到本地"，
        // 而缓存里只有 4878 字节（总共 4.4MB）—— 那是**播放器边播边写**留下的
        // 半截 span，不是下载。
        //
        // 根因：原来的 isCached() 在"台账里没有记录"时，只要缓存里有任意 span
        // 就返回 true。台账才是"主动下载完了"的凭据。
        val payload = ByteArray(48 * 1024)
        val url = startServer(payload)

        // 只读一小段就断开 —— 模拟"播放到一半"
        val ds = offline.buildCacheDataSource()
        ds.open(DataSpec(Uri.parse(url)))
        val buf = ByteArray(1024)
        ds.read(buf, 0, 1024)
        ds.close()

        assertTrue(
            "缓存里确实留下了 span（说明这个用例真的造出了半截缓存）",
            !offline.cache().getCachedSpans(OfflineDownloadManager.cacheKey(url)).isNullOrEmpty(),
        )
        assertFalse("半截缓存不能算已下载", offline.isCached(url))
    }

    @Test
    fun `真的下载完之后才算已下载`() = runBlocking {
        val payload = ByteArray(48 * 1024)
        val url = startServer(payload)

        assertTrue("下载应当成功", offline.download("art1", url, "标题", durationSec = 1))
        assertTrue("下载完成后应判定为已下载", offline.isCached(url))
    }

    @Test
    fun `下载后缓存里能查到该条目`() = runBlocking {
        val payload = ByteArray(16 * 1024)
        val url = startServer(payload)

        assertTrue("下载应当成功", offline.download("art1", url, "标题", durationSec = 1))

        val spans = offline.cache().getCachedSpans(OfflineDownloadManager.cacheKey(url))
        assertTrue("缓存里查不到这个条目", !spans.isNullOrEmpty())
        assertEquals(
            "缓存条目的总长度不对",
            payload.size.toLong(),
            spans.sumOf { it.length },
        )
    }
}
