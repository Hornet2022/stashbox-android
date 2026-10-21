package com.tingxia.audio.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
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
 *
 * ⚠️ 2026-10-03 实测结论（对定义处补标注，**没用**）：
 * 补 `@OptIn`（kotlin 版、androidx 版、位置参数、具名参数都试过）在本文件
 * 加上之后 lint error 数**一点没变**。这个类型的 opt-in 标注只有加在
 * **调用方**才被 lint 认 —— 所以 OfflineDownloadViewModel 那边也加了。
 * 这里的标注留给 Kotlin 编译器（编译期确实需要），但别指望它能消 lint。
 *
 * 连带说明：本类的**既有**调用方 DownloadButton / PlayerModule 同样在报
 * 同一个错（属那 16 个既有 media3 问题的范围，本次没动）。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@UnstableApi
@Singleton
class OfflineDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadedAudioDao,
) {
    /** 持久化缓存目录：<cacheDir>/audio_cache */
    private val cacheDir: File = File(context.cacheDir, "audio_cache")

    /**
     * 当前 SimpleCache 实例。
     *
     * 刻意**不用 `by lazy`**：登出时要把缓存 release 掉再换一个新的（见 [clearCache]），
     * 而 `by lazy` 的 val 一旦初始化就再也换不掉 —— release 之后播放器还握着一个
     * 已释放的 SimpleCache，再播就会撞上 "Cache is already released"。
     */
    @Volatile
    private var cacheInstance: Cache? = null

    /** 换缓存时的锁：保证「release 旧的」与「建新的」不会撞在一起。 */
    private val cacheLock = Any()

    private val cache: Cache
        get() = synchronized(cacheLock) {
            cacheInstance ?: SimpleCache(
                cacheDir,
                LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                StandaloneDatabaseProvider(context),
            ).also { cacheInstance = it }
        }

    /**
     * 为 ExoPlayer 提供带缓存的 DataSource.Factory。
     * PlayerController.initialize() + AudioPlayerService.onCreate() 调一次即可。
     *
     * ## 为什么下载也要走这个工厂（2026-10-21 真机 E2E）
     *
     * 离线下载和播放器必须写**同一个缓存条目**，否则下载了也白下 ——
     * 播放器读的是 key=CacheKeyFactory.DEFAULT(dataSpec) 的条目。
     *
     * media3 1.4.1 把 key **哈希**成 `<hash>.uid` 存盘，**没法从文件名反解出 key**：
     * - legacy 的 `SimpleCache.startFile` / `commitFile` 要靠解析文件名拿 key，
     *   面对哈希名只能返回 null → `Assertions.checkNotNull` 抛 NPE（message 为 null）
     *   → UI 上只剩一句「下载失败」，日志里什么都没有；
     * - v3 路径（`CacheDataSource` / `CacheWriter`）走 `CacheFileMetadataIndex`
     *   这张真实的索引表，不依赖文件名，所以能正常工作。
     *
     * 所以**下载必须用 v3 路径**，而且必须和播放器用**同一个工厂** ——
     * 否则 key、文件格式、flags 任何一处对不上，下载的文件播放器都读不到。
     *
     * @param upstreamFactory 仅供单测注入"上游被调用就炸"的假实现；
     *        生产走默认的 DefaultHttpDataSource。
     */
    fun buildCacheDataSourceFactory(
        upstreamFactory: DataSource.Factory = defaultUpstreamFactory(),
    ): DataSource.Factory {
        // 这里**不能**把 cache 实例直接焊进 CacheDataSource.Factory：
        // 那样播放器会永远握着建工厂那一刻的实例，登出 release 掉它之后再播
        // 就会撞上 "Cache is already released"。
        // 包一层：每次建 DataSource 时才去取**当前**的缓存实例，
        // 于是登出换缓存后播放器自动跟上，不需要重建 ExoPlayer。
        return DataSource.Factory { buildCacheDataSource(upstreamFactory) }
    }

    /**
     * 建一个带缓存的 DataSource（用的是**当前**的缓存实例）。
     *
     * 离线下载和播放器都必须经过这里 —— 它们的 key 工厂、flags、文件格式
     * 必须完全一致，否则下载的音频播放器读不到。
     */
    fun buildCacheDataSource(
        upstreamFactory: DataSource.Factory = defaultUpstreamFactory(),
    ): CacheDataSource = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        .createDataSource()

    /** 默认的上游：普通 HTTP 拉流。 */
    private fun defaultUpstreamFactory(): DataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(DEFAULT_TIMEOUT_MS)
            .setReadTimeoutMs(DEFAULT_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(DOWNLOAD_USER_AGENT)

    /**
     * 查某个 URL 是否**已完整**缓存在本地。
     *
     * ⚠️ 这里**不能**用 `Cache.isCached(key, position, length)`。它在
     * `androidx.media3.datasource.cache.Cache` 上是 abstract，由 `SimpleCache` 实现，
     * 而实现内部是 `Assertions.checkArgument(getCachedContent(key) != null)` 之后
     * 直接 `getCachedContent(key).getCachedBytesLength(...)` —— 对**不存在的 key 抛
     * IllegalArgumentException**。也就是说它不是「查一下在不在」，而是「断言它在」。
     *
     * 实测后果（华为 JEF-AN20，2026-10-02）：任何没下载过的文章，打开详情页必崩 ——
     *   java.lang.IllegalArgumentException
     *     at CachedContent.getCachedBytesLength(CachedContent.java:184)
     *     at SimpleCache.isCached(SimpleCache.java:461)
     *     at OfflineDownloadManager.isCached(OfflineDownloadManager.kt:93)
     *     at ArticleDetailScreen.kt:405
     * 上一轮只验证了「1.4.1 有没有公开 isCached」—— 方法确实存在（javap 可证），
     * 但**存在 ≠ 在这条调用路径上安全**，这是两件事。
     *
     * 安全查询用 `getCachedSpans`：Media3 保证 key 不存在时返回**空集合**而不是 null。
     * 再叠一层台账字节数校验，避免 LRU 只留下部分 span 时误报「已下载」。
     */
    suspend fun isCached(audioUrl: String): Boolean {
        // 2026-10-21 真机 E2E 修（此前会把"半截"误报成"已下载"）：
        //
        // 原来没有台账记录时，只要缓存里**有任意 span** 就返回 true。
        // 但"有 span"不等于"下载完了"——**播放器边播边写也会留下 span**，
        // 那是正在流的半截。真机上就复现了：用户点"下载到本地"，
        // 按钮立刻变成"已下载到本地"，而缓存里只有 4878 字节（总共 4.4MB）。
        //
        // 台账（downloaded_audio）才是"我们**主动**下载完了"的凭据：
        // 它只在下载流程走完时才写入，还带着真实的字节数。
        // 所以：**没有台账记录 = 没下载过**，不能靠 span 猜。
        //
        // 台账的完整性判据用实体自带的 `isComplete`（expectedBytes > 0 &&
        // cachedBytes >= expectedBytes）—— 注意**不是** `fileSizeBytes`：
        // 那一列是多码率变体用的遗留字段，下载路径从来不写它，
        // 读它等于永远读到 null，于是判据整个失效。
        val row = dao.byAudioUrl(audioUrl) ?: return false
        if (!row.isComplete) return false
        val spans = cache.getCachedSpans(cacheKey(audioUrl))
        if (spans.isNullOrEmpty()) return false
        // 再叠一层：LRU 可能把文件淘汰了，台账还写着"下完过"
        return spans.sumOf { it.length } >= row.expectedBytes
    }

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
            // ── 用 media3 官方的 CacheWriter 写缓存 ──────────────────────────
            //
            // 2026-10-21 真机 E2E 挖出来的：离线下载之前**恒失败**，
            // 而 CI 的单测是绿的。原因有三层，缺一层都跑不通：
            //
            // 1) **key 不一致**（最致命）
            //    播放器走 `CacheDataSource`，它的 key 由 `CacheKeyFactory.DEFAULT`
            //    哈希得到；下载路径原来直接用原始 URL。于是写的是两份不同的缓存，
            //    而且原始 URL 根本不是合法文件名 → commit 阶段
            //    `createCacheEntry` 解析不出 key → 返回 null →
            //    `Assertions.checkNotNull` 抛 NPE（message 为 null）→
            //    UI 上只剩一句「下载失败」。
            //    现在 key 统一走 `cacheKey()`（见 companion object 的说明）。
            //
            // 2) **前置条件**
            //    `SimpleCache.startFile` 字节码第一件事就是
            //    `contentIndex.get(key) → Assertions.checkNotNull(...)`，
            //    要求 content 条目已存在（那是 startReadWrite 才建的）。
            //    直接调 startFile → NPE。
            //
            // 3) **文件格式**
            //    legacy 的 `startFile` 写的是 `key.<index>`，
            //    而 `CacheDataSource` 写的是 `index.<ts>.v3.exo`。
            //    两套格式的 commit 互不认 —— 手写 legacy 那套，
            //    播放器根本读不到。
            //
            // `CacheWriter` 把这三件事一次解决：它内部就是
            // `CacheDataSource` + 同一个 `CacheKeyFactory.DEFAULT`，
            // key、锁、临时文件、commit 全部由 media3 自己管。
            //
            // 为什么以前没人发现：旧版 media3 没有这些前置条件；
            // 单测绕开了 download()（它要连真服务器）；
            // CI 里没有"真下载"这条路径。
            // 症状是内测用户点"下载"永远失败，而"通勤预下载"是产品的招牌功能。
            // 复用**播放器那个工厂** —— 这是"下载完播放器读得到"的前提。
            // 自己另建一个 CacheDataSource.Factory 的话，key 工厂 / flags /
            // 文件格式任何一处对不上，下载就白下了。
            val dataSource = buildCacheDataSource()

            var expected = 0L
            var written = 0L
            val writer = CacheWriter(
                dataSource,
                DataSpec(Uri.parse(audioUrl)),
                ByteArray(CacheWriter.DEFAULT_BUFFER_SIZE_BYTES),
            ) { bytesCached, newContentLength, _ ->
                written = bytesCached
                if (newContentLength > 0) {
                    expected = newContentLength
                    _states.value = _states.value +
                        (articleId to DownloadState.Downloading(bytesCached.toFloat() / newContentLength))
                }
            }

            try {
                writer.cache()
            } catch (e: Exception) {
                // 半截文件必须清掉，否则下次 isCached 会误判成"已下载"
                cache.removeResource(key)
                throw e
            }

            if (expected <= 0) expected = written

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
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            _states.value = _states.value +
                (articleId to DownloadState.DownloadError("HTTP ${e.responseCode}"))
            false
        } catch (e: Exception) {
            _states.value = _states.value +
                (articleId to DownloadState.DownloadError(e.message ?: "下载失败"))
            false
        }
    }

    /**
     * 台账里所有「已完整缓存」的音频 URL。
     *
     * 供 UI 订阅（StateFlow）而不是让组件同步逐条问 —— 组合期读磁盘是阻塞 IO，
     * 逐条问还是 N 次磁盘访问。
     */
    suspend fun cachedUrls(): Set<String> = withContext(Dispatchers.IO) {
        dao.all().map { it.audioUrl }.filter { isCached(it) }.toSet()
    }

    suspend fun remove(articleId: String, audioUrl: String) = withContext(Dispatchers.IO) {
        cache.removeResource(cacheKey(audioUrl))
        dao.delete(articleId)
        _states.value = _states.value - articleId
    }

    /**
     * 清空音频缓存（登出用）。
     *
     * 三步都不能省：
     * 1. `removeResource` 逐个删 —— 走 Media3 自己的 API，索引和文件一起清，
     *    不会留下"台账说已下载、文件其实还在"的残骸；
     * 2. `release` —— 释放目录锁。不 release 的话，下一个用户新建 SimpleCache
     *    会因为同一个目录已被占用而直接抛 IllegalStateException；
     * 3. 删掉缓存目录 —— 清掉索引文件与写了一半的临时文件，保证下一个用户
     *    拿到的是一个**全新的空目录**。
     *
     * 全部在 [cacheLock] 里做：中途若有别的线程来取缓存，不能让它建出一个
     * 半路被删掉的 SimpleCache。删完把实例置空，下次用到时 [cache] 会现建一个干净的。
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        synchronized(cacheLock) {
            val current = cacheInstance
            cacheInstance = null
            if (current != null) {
                // 这两个调用都不是挂起函数，不会被取消，吞掉异常即可
                runCatching { current.getKeys().toList().forEach { current.removeResource(it) } }
                runCatching { current.release() }
            }
            runCatching { cacheDir.deleteRecursively() }
        }
        // 下载状态是内存里的用户数据，一并清掉
        _states.value = emptyMap()
    }

    /**
     * 用 Cache 的真实状态校准 Room 台账：LRU 淘汰掉的文件，对应的行一并删。
     *
     * 不做这件事就会出现「列表显示已下载、点开报网络错误」——
     * 表说有、文件没了，比没有这层更糟。
     */
    suspend fun reconcileWithCache() = withContext(Dispatchers.IO) {
        // 走 isCached 而不是直接调 Cache.isCached —— 同样的 IllegalArgumentException
        // 陷阱，这里是第二个调用点（LRU 对账在 App 启动期跑，等于启动即崩）。
        val stale = dao.all().filterNot { isCached(it.audioUrl) }
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
         * 下载用的 User-Agent。
         *
         * 为什么不用 OkHttp 默认的：部分 CDN/静态托管会对没有 UA 的请求给 403，
         * 而播放器和下载器是两个独立的 HTTP 栈，UA 不一致容易被区别对待。
         * 带上 UA 让下载请求和播放请求看起来像同一类客户端。
         */
        const val DOWNLOAD_USER_AGENT: String = "Tingxia/0.1.0 (Android)"

        /**
         * 缓存条目 key —— **必须和播放器用的是同一个**。
         *
         * 2026-10-21 真机 E2E 挖出来的坑：
         * media3 1.4.1 的 `CacheDataSource`（播放器走它）不是直接用 URL 当 key，
         * 而是经 `CacheKeyFactory.DEFAULT` **哈希**成一个安全字符串
         * （设备上实测缓存文件名是 `1b9a4ac57b74997b.uid` 这种 16 位十六进制）。
         *
         * 而这里原来直接返回原始 URL —— 于是：
         * - 下载写的是 key=URL 的条目，播放器读的是 key=hash(URL) 的条目，
         *   **两者根本不是同一份缓存**：下载成功了播放器也读不到；
         * - 原始 URL 里有 `://` 和 `/`，根本不是合法的文件名，
         *   commitFile 阶段 `createCacheEntry` 解析不出 key → 返回 null →
         *   `Assertions.checkNotNull` 抛 NPE（**message 为 null**）→
         *   UI 上只剩一句没头没脑的「下载失败」。
         *
         * 所以 key 必须走同一个 CacheKeyFactory.DEFAULT。
         */
        fun cacheKey(audioUrl: String): String =
            CacheKeyFactory.DEFAULT.buildCacheKey(DataSpec(Uri.parse(audioUrl)))
    }
}

/** 缓存状态机。 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data object Downloaded : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data class DownloadError(val message: String) : DownloadState()
}
