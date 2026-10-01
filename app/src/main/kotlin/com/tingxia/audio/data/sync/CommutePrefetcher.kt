package com.tingxia.audio.data.sync

import android.content.Context
import android.util.Log
import com.tingxia.audio.audio.OfflineDownloadManager
import com.tingxia.audio.data.local.DownloadedAudioDao
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.DistillStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通勤自动预加载的**决策层**（2026-10-02）。
 *
 * 之前 [PrefetchScheduler] 只有入口、没有任何调用方 —— 也就是说
 * 「通勤时段自动预加载」这个方案承诺的功能压根没接上。现在这里是决策：
 * 什么时候该预加载、预哪几篇、以及**什么时候不该**。
 *
 * 几条「不该」，比「该」更重要：
 * - 已在缓存里的不重复下
 * - 存储不够的不下（100MB LRU 已经很紧，再挤会淘汰用户已下的）
 * - 不在通勤窗口的不下（别在办公室后台偷偷下一堆）
 * - 正在 WiFi 下且文章不多时也不下 —— 用户马上就能播，下载只是浪费流量
 */
@Singleton
class CommutePrefetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduler: PrefetchScheduler,
    private val offlineDownloadManager: OfflineDownloadManager,
    private val downloadedAudioDao: DownloadedAudioDao,
) {

    /**
     * 列表加载完时调一次。按决策结果排预加载任务。
     *
     * @return 实际排入的任务数（用于日志/测试断言）
     */
    suspend fun maybePrefetch(articles: List<Article>, learnedWindows: List<IntRange>?): Int =
        withContext(Dispatchers.IO) {
            if (!CommuteWindowDetector.shouldPrefetchNow(learnedWindows)) {
                Log.i(TAG, "不在通勤窗口，跳过预加载")
                return@withContext 0
            }

            // 存储够不够：留 50MB 余量给系统
            val free = context.filesDir.usableSpace
            if (free < MIN_FREE_BYTES) {
                Log.w(TAG, "可用存储不足 ${free / 1024 / 1024}MB，跳过预加载")
                return@withContext 0
            }

            val existing = offlineDownloadManager.reconcileWithCache().toSet()

            val candidates = articles
                .filter { it.status == DistillStatus.READY && !it.audioUrl.isNullOrBlank() }
                // 正在播的那篇不预加载（它正在下）
                .filterNot { existing.contains(it.id) }

            var queued = 0
            for (article in candidates) {
                if (queued >= CommuteWindowDetector.prefetchCount()) break
                if (offlineDownloadManager.isCached(article.audioUrl!!)) continue
                scheduler.prefetchAudio(
                    articleId = article.id,
                    audioUrl = article.audioUrl,
                    title = article.title,
                    durationSec = article.durationSec,
                )
                queued++
            }
            Log.i(TAG, "通勤窗口内排入预加载 $queued 篇")
            queued
        }

    companion object {
        private const val TAG = "CommutePrefetcher"

        /** 低于这个余量就不预加载。100MB LRU 很紧，别把用户已下的挤掉。 */
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024
    }
}
