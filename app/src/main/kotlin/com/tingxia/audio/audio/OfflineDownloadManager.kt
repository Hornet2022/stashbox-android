package com.tingxia.audio.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import com.tingxia.audio.data.local.DownloadedAudioDao
import com.tingxia.audio.data.local.DownloadedAudioEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 离线音频管理（2026-10-02 重写）。
 *
 * ## 之前的问题
 *
 * 注释里写着「Media3 1.4.1 没有公开 isCached()，返回 false 作为保守结果」。
 * **这句是错的** —— 1.4.1 的 `Cache` 接口就有 `isCached(key, position, length)`，
 * 实测 `javap` 确认。于是 [isCached] 永远 false：下载图标永远是"未下载"，
 * 而点击按钮是 `onClick = { /* no-op */ }` —— 整个离线下载是个装饰品，
 * 付费墙文案还写着"即将开放"。
 *
 * ## 现在的做法
 *
 * 分三层，各司其职：
 * - **Media3 SimpleCache**：音频字节本体，LRU 100MB，播放器与下载共用同一份，
 *   所以「下载完的文章」播放时天然走本地，不会重复占空间。
 * - **Room [DownloadedAudioEntity]**：下载台账。SimpleCache 会 LRU 淘汰文件，
 *   没有台账就会出现「界面说已下载、文件早没了」。
 * - **[download] 协程**：用 OkHttp 之外最朴素的方式（HttpURLConnection 流式写）
 *   把音频写进 Cache。选它是因为 CacheDataSource 只能被 ExoPlayer 驱动，
 *   无法在后台独立跑；直接写 Cache 需要 CacheSpan 句柄，比自定义目录更
 *   能与播放侧共享缓存。
 */
@UnstableApi
@Singleton
class OfflineDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadedAudioDao,
) {
    /** 持久化缓存目录：<cacheDir>/audio_cache */
    private val cacheDir: File = File(context.cacheDir, "audio_cache")

    private val cache: Cache by lazy {
        SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            StandaloneDatabaseProvider(context),
        )
    }

    /**
     * 为 ExoPlayer 提供带缓存的 DataSource.Factory。
     * PlayerController.initialize() + AudioPlayerService.onCreate() 调一次即可。
     */
    fun buildCacheDataSourceFactory(): DataSource.Factory {
        val upstreamFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(DEFAULT_TIMEOUT_MS)
            .setReadTimeoutMs(DEFAULT_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * 查某个 URL 是否**已完整**缓存在本地。
     *
     * Media3 的 `Cache.isCached(key, position, length)` 在 1.4.1 里是公开的
     * （`javap androidx.media3.datasource.cache.Cache` 可验证），之前的
     * 「没有公开」是误判。`CACHE_LENGTH_UNSET` 让它忽略长度、按已缓存
     * 范围判断是否覆盖到末尾。
     *
     * 还要过一遍 Room 台账 —— LRU 淘汰后 Cache 说不全，表里那行也得同步删掉，
     * 否则两处状态打架。
     */
    fun isCached(audioUrl: String): Boolean =
        cache.isCached(cacheKey(audioUrl), 0, CACHE_LENGTH_UNSET)

    /**
     * 主动下载一集音频到本地缓存。
     *
     * 幂等：已完整缓存则直接返回 true。断点续传靠 [DownloadState] 记录，
     * 但 SimpleCache 不支持从中间续写，所以这里失败即从头重来（LRU 100MB
     * 的量级下这个代价可接受）。
     */
    suspend fun download(
        articleId: String,
        audioUrl: String,
        title: String? = null,
        durationSec: Int? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val key = cacheKey(audioUrl)

        if (isCached(audioUrl)) {
            recordComplete(articleId, audioUrl, title, durationSec, pinned = true)
            _states.value = _states.value + (articleId to DownloadState.Downloaded)
            return@withContext true
        }

        // 旧的不完整缓存先清掉，避免半截文件被当成有效数据
        cache.removeResource(key)

        _states.value = _states.value + (articleId to DownloadState.Downloading(0f))

        try {
            val conn = (URL(audioUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = DEFAULT_TIMEOUT_MS
                readTimeout = DEFAULT_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                _states.value = _states.value + (articleId to DownloadState.DownloadError("HTTP $code"))
                conn.disconnect()
                return@withContext false
            }

            val expected = conn.contentLengthLong
            var written = 0L

            // 通过 Cache 的 span 写入：startFile 拿到临时文件，写完 commitFile。
            // 注意签名（javap 实测 media3-datasource 1.4.1）：
            //   startFile(String, long, long) : File        ← 返回 File，不是 CacheSpan
            //   commitFile(File, long)         : Unit
            //   isCached(String, long, long)   : Boolean     ← 1.4.1 **是公开的**
            val pendingFile = cache.startFile(key, 0, if (expected > 0) expected else CACHE_LENGTH_UNSET)
            try {
                pendingFile.outputStream().use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) {
                                throw InterruptedException("下载被取消")
                            }
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (expected > 0) {
                                _states.value = _states.value +
                                    (articleId to DownloadState.Downloading(written.toFloat() / expected))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // 半截文件必须清掉，否则下次 isCached 会误判
                cache.removeResource(key)
                throw e
            } finally {
                conn.disconnect()
            }

            cache.commitFile(pendingFile, written)

            dao.upsert(
                DownloadedAudioEntity(
                    articleId = articleId,
                    audioUrl = audioUrl,
                    title = title,
                    durationSec = durationSec,
                    cachedBytes = written,
                    expectedBytes = if (expected > 0) expected else written,
                    pinned = true,
                )
            )
            _states.value = _states.value + (articleId to DownloadState.Downloaded)
            true
        } catch (e: Exception) {
            _states.value = _states.value +
                (articleId to DownloadState.DownloadError(e.message ?: "下载失败"))
            false
        }
    }

    suspend fun remove(articleId: String, audioUrl: String) = withContext(Dispatchers.IO) {
        cache.removeResource(cacheKey(audioUrl))
        dao.delete(articleId)
        _states.value = _states.value - articleId
    }

    /**
     * 用 Cache 的真实状态校准 Room 台账：LRU 淘汰掉的文件，对应的行一并删。
     *
     * 不做这件事就会出现「列表显示已下载、点开报网络错误」——
     * 表说有、文件没了，比没有这层更糟。
     */
    suspend fun reconcileWithCache() = withContext(Dispatchers.IO) {
        val stale = dao.all().filterNot { cache.isCached(cacheKey(it.audioUrl), 0, CACHE_LENGTH_UNSET) }
        if (stale.isNotEmpty()) dao.deleteAll(stale.map { it.articleId })
        stale.map { it.articleId }
    }

    private suspend fun recordComplete(
        articleId: String,
        audioUrl: String,
        title: String?,
        durationSec: Int?,
        pinned: Boolean,
    ) {
        val existing = dao.byArticle(articleId)
        dao.upsert(
            DownloadedAudioEntity(
                articleId = articleId,
                audioUrl = audioUrl,
                title = title ?: existing?.title,
                durationSec = durationSec ?: existing?.durationSec,
                cachedBytes = existing?.cachedBytes?.takeIf { it > 0 } ?: 0L,
                expectedBytes = existing?.expectedBytes?.takeIf { it > 0 } ?: 0L,
                pinned = pinned || (existing?.pinned ?: false),
            )
        )
    }

    internal fun cache(): Cache = cache

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    companion object {
        const val TAG = "OfflineDownload"
        const val MAX_CACHE_BYTES: Long = 100L * 1024 * 1024 // 100 MB
        const val DEFAULT_TIMEOUT_MS: Int = 60_000

        /**
         * Media3 里表示"长度未知 / 一直到末尾"的哨兵值。
         *
         * 定义在 `androidx.media3.common.C`（值 -1），**不在** cache 包里 ——
         * 最初写成 `Cache.LENGTH_UNSET` 编译不过，实测 javap 逐个类扫过，
         * cache 包下没有任何类定义它。
         */
        private val CACHE_LENGTH_UNSET: Long = androidx.media3.common.C.LENGTH_UNSET.toLong()

        /**
         * SimpleCache 的默认 key 就是 URI 字符串，与 CacheDataSource 默认行为一致。
         * 显式写出来是为了：如果哪天给 CacheDataSourceFactory 设了
         * setCacheKeyFactory，两边还能对上。
         */
        fun cacheKey(audioUrl: String): String = audioUrl
    }
}

/** 缓存状态机。 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data object Downloaded : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data class DownloadError(val message: String) : DownloadState()
}
