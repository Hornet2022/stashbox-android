package com.tingxia.audio.data.sync

import android.content.Context
import com.tingxia.audio.audio.OfflineDownloadManager
import com.tingxia.audio.audio.VariantSelectionUseCase
import com.tingxia.audio.data.FakeDistillationApi
import com.tingxia.audio.data.local.DownloadedAudioDao
import com.tingxia.audio.data.local.DownloadedAudioEntity
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.AudioVariant
import com.tingxia.audio.data.model.AudioVariantsResponse
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.model.VariantWarmResponse
import com.tingxia.audio.data.repository.VariantRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 自动通勤预加载必须下**通勤档（64k）**，不是原文 WAV（2026-10-21）。
 *
 * ## 为什么这是缺陷而不是优化
 *
 * 通勤预加载页的文案写着「在 Wi-Fi 环境批量转码 64k 档（流量友好）」，
 * 手动批量预热（`OfflineDownloadViewModel.batchWarm`）确实照做了：
 * warm → pick → download。但**自动**那条路（`CommutePrefetcher`）直接把
 * `article.audioUrl` 丢给调度器 —— 没有预热、没有选档，下的是 24kHz 原文。
 *
 * 真后端实测同一篇文章：
 * - 原文 WAV：4,471,054 字节 / 93.1s ≈ 48 KB/s
 * - 64k m4a：723,797 字节 / 84s ≈ 8.6 KB/s（小 5.5 倍）
 *
 * 设备上的 SimpleCache 只有 100MB，装原文只够 ~36 分钟（够装 3.3 小时的
 * 64k 变体），而且原文会把 LRU 缓存迅速灌满、静默淘汰用户自己下过的东西。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CommutePrefetcherVariantTest {

    /** 原文音频地址（24kHz WAV 的那种）。 */
    private val originalUrl = "https://cdn.example.com/audio/main.wav"

    /** 通勤档（64k m4a）地址。 */
    private val variantUrl = "https://cdn.example.com/audio/64k.m4a"

    /** 可编程的变体接口替身（继承仓库里那份默认实现，只覆写关心的方法）。 */
    private class FakeVariantApi : FakeDistillationApi() {
        /** 每次 warm 的 (taskId, bitrate)，用来断言"预热的是 64k 档"。 */
        val warmCalls = mutableListOf<Pair<String, Int>>()
        var failWarm = false
        var failVariants = false

        override suspend fun warmVariant(taskId: String, bitrate: Int): VariantWarmResponse {
            warmCalls += taskId to bitrate
            if (failWarm) throw RuntimeException("模拟 warm 失败")
            return VariantWarmResponse(taskId = taskId, bitrate = bitrate, generated = true)
        }

        override suspend fun getVariants(taskId: String): AudioVariantsResponse {
            if (failVariants) throw RuntimeException("模拟 variants 失败")
            return AudioVariantsResponse(
                taskId = taskId,
                articleId = "art_1",
                variants = listOf(
                    AudioVariant(
                        bitrate = 128,
                        available = true,
                        url = "https://cdn.example.com/audio/main.wav",
                        isMain = true,
                    ),
                    AudioVariant(bitrate = 64, available = true, url = "https://cdn.example.com/audio/64k.m4a"),
                ),
            )
        }
    }

    /** 台账替身：预加载路径只读它，不写。 */
    private class FakeDao : DownloadedAudioDao {
        val rows = mutableMapOf<String, DownloadedAudioEntity>()
        override suspend fun upsert(entity: DownloadedAudioEntity) {
            rows[entity.articleId] = entity
        }
        override suspend fun byArticle(articleId: String): DownloadedAudioEntity? = rows[articleId]
        override fun observeByArticle(articleId: String): Flow<DownloadedAudioEntity?> =
            flowOf(rows[articleId])
        override suspend fun byAudioUrl(audioUrl: String): DownloadedAudioEntity? =
            rows.values.firstOrNull { it.audioUrl == audioUrl }
        override suspend fun allPinned(): List<DownloadedAudioEntity> = rows.values.filter { it.pinned }
        override suspend fun all(): List<DownloadedAudioEntity> = rows.values.toList()
        override suspend fun totalCachedBytes(): Long = rows.values.sumOf { it.cachedBytes }
        override suspend fun delete(articleId: String) {
            rows.remove(articleId)
        }
        override suspend fun deleteAll(articleIds: List<String>) {
            articleIds.forEach { rows.remove(it) }
        }
        override suspend fun clearAll() {
            rows.clear()
        }
    }

    /**
     * 把预加载器的两个"环境依赖"（WorkManager 调度、当前时间）换成可观测的替身。
     *
     * WorkManager 在 Robolectric 里没初始化，真调会抛 IllegalStateException；
     * 通勤窗口默认是早 7-9 / 晚 18-20，测试跑在半夜就永远不预加载。
     */
    private class RecordingPrefetcher(
        context: Context,
        dao: DownloadedAudioDao,
        resolver: CommuteVariantResolver,
    ) : CommutePrefetcher(
        context = context,
        scheduler = PrefetchScheduler(context),
        offlineDownloadManager = OfflineDownloadManager(context, dao),
        downloadedAudioDao = dao,
        variantResolver = resolver,
    ) {
        /** 排进去的 (articleId, url)。 */
        val queued = mutableListOf<Pair<String, String>>()

        override fun enqueuePrefetch(
            articleId: String,
            audioUrl: String,
            title: String?,
            durationSec: Int?,
        ) {
            queued += articleId to audioUrl
        }

        override fun isCommuteWindow(learnedWindows: List<IntRange>?): Boolean = true
    }

    private fun resolver(api: FakeVariantApi): CommuteVariantResolver {
        val context = RuntimeEnvironment.getApplication()
        val repository = VariantRepository(api)
        return CommuteVariantResolver(repository, VariantSelectionUseCase(context, repository))
    }

    private fun readyArticle() = Article(
        id = "art_1",
        title = "测试文章",
        status = DistillStatus.READY,
        taskId = "dst_1",
        audioUrl = originalUrl,
        durationSec = 93,
    )

    @Test
    fun `预加载请求的是预热过的通勤档 URL`() = runBlocking {
        val api = FakeVariantApi()
        val prefetcher = RecordingPrefetcher(
            context = RuntimeEnvironment.getApplication(),
            dao = FakeDao(),
            resolver = resolver(api),
        )

        val queued = prefetcher.maybePrefetch(listOf(readyArticle()), learnedWindows = null)

        assertEquals("应该排入 1 篇", 1, queued)
        assertEquals("排的必须是通勤档 URL，不是原文 WAV", variantUrl, prefetcher.queued.single().second)
        assertEquals(
            "必须先让服务端预热 64k 档",
            listOf("dst_1" to 64),
            api.warmCalls,
        )
    }

    @Test
    fun `变体预热失败时退回原文_预加载不能挂`() = runBlocking {
        val api = FakeVariantApi().apply { failWarm = true }
        val prefetcher = RecordingPrefetcher(
            context = RuntimeEnvironment.getApplication(),
            dao = FakeDao(),
            resolver = resolver(api),
        )

        val queued = prefetcher.maybePrefetch(listOf(readyArticle()), learnedWindows = null)

        assertEquals("变体失败也要排预加载（退回原文）", 1, queued)
        assertEquals("退回的应该是原文 URL", originalUrl, prefetcher.queued.single().second)
    }

    @Test
    fun `变体协商失败时退回原文_预加载不能挂`() = runBlocking {
        val api = FakeVariantApi().apply { failVariants = true }
        val prefetcher = RecordingPrefetcher(
            context = RuntimeEnvironment.getApplication(),
            dao = FakeDao(),
            resolver = resolver(api),
        )

        val queued = prefetcher.maybePrefetch(listOf(readyArticle()), learnedWindows = null)

        assertEquals(1, queued)
        assertEquals("退回的应该是原文 URL", originalUrl, prefetcher.queued.single().second)
    }

    @Test
    fun `没有 task_id 时退回原文`() = runBlocking {
        val api = FakeVariantApi()
        val resolver = resolver(api)

        val url = resolver.resolvePrefetchUrl(null, originalUrl)

        assertEquals("没有 task_id 就该退回原文", originalUrl, url)
        assertTrue("没有 task_id 就不该去调预热", api.warmCalls.isEmpty())
    }

    @Test
    fun `解析器单独用时也返回通勤档 URL`() = runBlocking {
        val api = FakeVariantApi()

        val url = resolver(api).resolvePrefetchUrl("dst_1", originalUrl)

        assertEquals(variantUrl, url)
    }

    @Test
    fun `两条路径共用同一个档位常量`() {
        // 手动批量预热与自动预加载必须下同一档，否则台账 URL 与缓存条目对不上
        assertEquals(64, COMMUTE_PREFETCH_BITRATE)
    }
}
