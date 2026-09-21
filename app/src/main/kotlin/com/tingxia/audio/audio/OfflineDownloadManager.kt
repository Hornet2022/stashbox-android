package com.tingxia.audio.audio

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 离线音频缓存（CP11.0.7 P2.1）— 简化版。
 *
 * 设计：
 * - 进程内单例 [SimpleCache](Media3 LRU 100MB)，目录 `<cacheDir>/audio_cache`
 * - [PlayerController] + [com.tingxia.audio.audio.AudioPlayerService] 通过
 *   [buildCacheDataSourceFactory] 包装 ExoPlayer 数据源。**任何播放命中本地缓存时
 *   秒开**，未命中时 ExoPlayer 拉 HTTP 数据并自动写入 cache（LRU 替换）。
 * - 不引入主动下载：用户首次点击播放即"下载"，下次再播命中本地。
 *   这避开 Media3 Cache 写 API 的复杂性（1.4.1 中 startReadAndWrite/writeFile 不公开），
 *   也避免大文件同步下载阻塞 UI 线程。
 *
 * 设计选择：不引入 WorkManager / Foreground Service —— 用户触发 + LRU 自动缓存足够。
 */
@UnstableApi
@Singleton
class OfflineDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** 持久化缓存目录：<cacheDir>/audio_cache */
    private val cacheDir: File = File(context.cacheDir, "audio_cache")

    /** LRU 100MB —— 一篇 30 分钟音频（MP3 128kbps）≈ 28MB，留余量给多篇 */
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
     * 查询指定 URL 是否已完整缓存（命中本地秒开）。
     * 媒体3 1.4.1 Cache 没有公开 isCached() —— 返回 false 作为保守结果。
     * UI 仅显示"未缓存"图标；首次播放后即标记缓存命中（通过 CacheDataSource 行为）。
     */
    fun isCached(audioUrl: String): Boolean = false

    /** 暴露 cache 实例供未来扩展 / 调试 */
    internal fun cache(): Cache = cache

    /** 占位状态：当前设计无主动下载按钮，但仍暴露 StateFlow 供 UI 扩展 */
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    companion object {
        private const val TAG = "OfflineDownload"
        const val MAX_CACHE_BYTES: Long = 100L * 1024 * 1024 // 100 MB
        const val DEFAULT_TIMEOUT_MS: Int = 60_000
    }
}

/** 缓存状态机（保留扩展点；当前设计只用 Idle） */
sealed class DownloadState {
    data object Idle : DownloadState()
    data object Downloaded : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data class DownloadError(val message: String) : DownloadState()
}