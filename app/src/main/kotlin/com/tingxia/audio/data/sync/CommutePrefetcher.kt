// Media3 把大量播放/缓存 API 标为 @UnstableApi。
// 这些文件直接使用 ExoPlayer / SimpleCache / DownloadManager 等 unstable 声明，
// 必须显式 opt-in，否则 :app:lintDebug 报 UnsafeOptInUsageError 直接让 CI 失败。
//
// 注意用的是 **androidx.annotation.OptIn** 而不是 kotlin.OptIn：Media3 的
// UnstableApi 标的是 androidx 的 @RequiresOptIn，lint 认的是前者；写 Kotlin 的
// @OptIn 只会让 lint 在这行本身上再报一次（实测错误数 16 → 22）。
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
package com.tingxia.audio.data.sync

import android.content.Context
import android.util.Log
import com.tingxia.audio.audio.OfflineDownloadManager
import com.tingxia.audio.audio.VariantSelectionUseCase
import com.tingxia.audio.data.local.DownloadedAudioDao
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.repository.VariantRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通勤预加载 / 预热的**目标码率**（kbps）。
 *
 * ## 为什么是 64 而不是主档 128
 *
 * 同一篇文章在真后端上实测：
 * - 原文 WAV（24kHz / 16bit / mono）：4,471,054 字节 / 93.1s ≈ **48 KB/s**
 * - 64k m4a 变体：723,797 字节 / 84s ≈ **8.6 KB/s**（小 5.5 倍）
 *
 * 而设备上的 SimpleCache 只有 100MB（[OfflineDownloadManager.MAX_CACHE_BYTES]），
 * 所以装原文只够 ~36 分钟音频，装 64k 变体够 ~3.3 小时。走原文还会让 LRU
 * 缓存被迅速灌满，静默淘汰掉用户自己下过的东西。
 *
 * ## 为什么两条路径必须共用这一个常量
 *
 * 手动批量预热（[com.tingxia.audio.ui.offline.OfflineDownloadViewModel]）和
 * 自动通勤预加载（本文件的 [CommutePrefetcher]）下到本机的必须是**同一档**，
 * 否则台账里记的 URL 和缓存里的条目对不上，缓存对账会认为"下了但没下过"。
 */
internal const val COMMUTE_PREFETCH_BITRATE = 64

/**
 * 通勤预加载的**档位解析**（2026-10-21）。
 *
 * ## 修的是什么
 *
 * 自动预加载原来把 `article.audioUrl`（**原文 WAV**）直接丢给
 * [PrefetchScheduler.prefetchAudio] —— 没有预热、没有选档。
 * 页面文案写着「在 Wi-Fi 环境批量转码 64k 档（流量友好）」，手动批量预热
 * 确实照做了（warm → pick → download），自动这条路径却没跟上：
 * 用户在通勤时段自动下的永远是 5.5 倍大的原文。
 *
 * ## 为什么单独一个类
 *
 * 「这篇该下哪个 URL」是这段逻辑里唯一有业务含义的部分，而它必须能在
 * **不启动 WorkManager、不建 Media3 缓存**的条件下被单测覆盖 ——
 * 塞在 [CommutePrefetcher] 里就只能靠"读源码断言"那种弱测试来锁。
 */
class CommuteVariantResolver @Inject constructor(
    private val variantRepository: VariantRepository,
    private val variantSelection: VariantSelectionUseCase,
) {

    /**
     * 解析「这篇该下载哪个 URL」。
     *
     * 顺序与手动批量预热一致：
     * 1. [VariantRepository.warmVariant] —— 让服务端转码出通勤档（幂等，重复调不重转）
     * 2. [VariantSelectionUseCase.pick] —— 协商并取该档的可下载 URL
     *
     * 场景固定用 [VariantSelectionUseCase.Scenario.OFFLINE_PRELOAD]：
     * 预加载的目的地是"地铁里没网时播"，档位由**用途**决定，不由当下的
     * 网络决定（人在 Wi-Fi 下不代表地铁里有 Wi-Fi）。
     *
     * @param taskId 蒸馏任务 id（变体接口以它为 key；文章列表接口会带上）
     * @param originalUrl 原文音频地址 —— 任何一步失败时的兜底
     * @return 通勤档 URL；拿不到就返回 [originalUrl]
     */
    suspend fun resolvePrefetchUrl(taskId: String?, originalUrl: String): String {
        if (taskId.isNullOrBlank()) {
            // ready 的文章一定带 task_id（列表接口的 task_id 来自同一条蒸馏任务行），
            // 真没有就说明数据不完整 —— 退回原文，别把预加载搞挂。
            Log.w(TAG, "文章没有 task_id，退回原文")
            return originalUrl
        }

        val variantUrl = try {
            // 1. 服务端转码出通勤档（没有这一步，服务端可能还没这一档）
            variantRepository.warmVariant(taskId, COMMUTE_PREFETCH_BITRATE)
            // 2. 取该档 URL —— warm 只保证服务端有货，URL 得再协商一次
            variantSelection.pick(taskId, VariantSelectionUseCase.Scenario.OFFLINE_PRELOAD).url
        } catch (e: CancellationException) {
            // 协程被取消**不是**"变体不可用"，吞掉会让上层以为预加载成功了。
            // （同 [com.tingxia.audio.auth.AuthRepository] 里对 runCatching 的提醒：
            //  runCatching 连 CancellationException 一起吞。）
            throw e
        } catch (e: Exception) {
            // 变体失败**绝不能**拖垮预加载：退回原文，功能照旧，只是流量费点。
            Log.w(TAG, "通勤档预热失败（$e），退回原文")
            null
        }

        if (variantUrl.isNullOrBlank()) {
            Log.w(TAG, "通勤档不可用，退回原文")
            return originalUrl
        }
        Log.i(TAG, "通勤档 $COMMUTE_PREFETCH_BITRATE kbps 可用：$variantUrl")
        return variantUrl
    }

    companion object {
        private const val TAG = "CommuteVariantResolver"
    }
}

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
 *
 * 2026-10-21 修：下的是**通勤档（64k）**，不是原文 WAV。
 * 详见 [CommuteVariantResolver]。
 */
@Singleton
open class CommutePrefetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduler: PrefetchScheduler,
    private val offlineDownloadManager: OfflineDownloadManager,
    private val downloadedAudioDao: DownloadedAudioDao,
    private val variantResolver: CommuteVariantResolver,
) {

    /**
     * 列表加载完时调一次。按决策结果排预加载任务。
     *
     * @return 实际排入的任务数（用于日志/测试断言）
     */
    suspend fun maybePrefetch(articles: List<Article>, learnedWindows: List<IntRange>?): Int =
        withContext(Dispatchers.IO) {
            if (!isCommuteWindow(learnedWindows)) {
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
                val originalUrl = article.audioUrl ?: continue
                if (offlineDownloadManager.isCached(originalUrl)) continue

                // 2026-10-21：下**通勤档**，不是原文。
                // 解析失败会自动退回 originalUrl，所以这一行不会抛。
                val url = variantResolver.resolvePrefetchUrl(article.taskId, originalUrl)

                enqueuePrefetch(
                    articleId = article.id,
                    audioUrl = url,
                    title = article.title,
                    durationSec = article.durationSec,
                )
                queued++
            }
            Log.i(TAG, "通勤窗口内排入预加载 $queued 篇")
            queued
        }

    /**
     * 排一次预加载下载。
     *
     * 抽成 open 方法只为让单测能在**不启动 WorkManager** 的前提下断言
     * "到底排了哪个 URL"（WorkManager 在 Robolectric 里没初始化，
     * 真调会抛 IllegalStateException）。生产路径就是转调 [PrefetchScheduler]。
     */
    protected open fun enqueuePrefetch(
        articleId: String,
        audioUrl: String,
        title: String?,
        durationSec: Int?,
    ) {
        scheduler.prefetchAudio(articleId, audioUrl, title, durationSec)
    }

    /**
     * 现在是不是通勤窗口。
     *
     * 抽成 open 方法只为让单测不依赖"现在几点"—— 默认窗口是早 7-9 / 晚 18-20，
     * 测试跑在半夜就永远不预加载。
     */
    protected open fun isCommuteWindow(learnedWindows: List<IntRange>?): Boolean =
        CommuteWindowDetector.shouldPrefetchNow(learnedWindows)

    companion object {
        private const val TAG = "CommutePrefetcher"

        /** 低于这个余量就不预加载。100MB LRU 很紧，别把用户已下的挤掉。 */
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024
    }
}
