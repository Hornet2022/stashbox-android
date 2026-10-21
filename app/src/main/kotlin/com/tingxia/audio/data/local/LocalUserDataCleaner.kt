// Media3 把大量播放/缓存 API 标为 @UnstableApi。
// 本文件直接使用 OfflineDownloadManager（它整体标了 @UnstableApi），
// 必须显式 opt-in，否则 :app:lintDebug 报 UnsafeOptInUsageError 直接让 CI 失败。
//
// 注意用的是 **androidx.annotation.OptIn** 而不是 kotlin.OptIn：Media3 的
// UnstableApi 标的是 androidx 的 @RequiresOptIn，lint 认的是前者。
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
package com.tingxia.audio.data.local

import android.util.Log
import androidx.room.withTransaction
import com.tingxia.audio.audio.OfflineDownloadManager
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.data.sync.PrefetchScheduler
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「清空本机用户数据」能力（2026-10-21）。
 *
 * 抽成接口而不是直接写死在 [com.tingxia.audio.auth.AuthRepository] 里，
 * 是为了让登出的测试能塞一个假实现进去 —— 本文件里的三样东西
 * （Room 库 / Media3 缓存 / WorkManager）都不是好 mock 的。
 */
interface UserDataCleaner {
    /**
     * 清空上一个用户留在本机的全部数据。
     *
     * 约定：
     * - **离线可用**：不依赖网络，服务端挂了也必须清得掉；
     * - **可重复调用**：没登录过 / 已经清空了，再调一次不崩、不抛；
     * - **不吞协程取消**：取消照常往上抛（只有真正的失败才被吞掉）。
     */
    suspend fun clearAll()
}

/**
 * 默认实现：把「上一个用户在本机留下的一切」逐样清掉。
 *
 * ## 为什么登出必须清这些
 *
 * 之前 [com.tingxia.audio.auth.AuthRepository.logout] 只清了 DataStore 里的 JWT，
 * 于是本机还留着上一个用户的：
 * - **下载台账 + 播放进度**（Room `tingxia.db`）
 * - **音频字节本体**（Media3 SimpleCache，`<cacheDir>/audio_cache`，LRU 100MB）
 *
 * 共享手机（内测时 testers 互相传手机）的情况下，下一个登录的人会直接看到
 * 上一个人的下载列表、续播位置和本地缓存 —— 这是跨账号的数据泄漏。
 */
@Singleton
class LocalUserDataCleaner @Inject constructor(
    private val database: AppDatabase,
    private val offlineDownloadManager: OfflineDownloadManager,
    private val prefetchScheduler: PrefetchScheduler,
    private val playerController: PlayerController,
) : UserDataCleaner {

    override suspend fun clearAll() {
        // ── 顺序是硬要求 ──────────────────────────────────────────────
        //
        // ① **先停播放器**
        //    播放进度每 10s 往 Room 的 playback_progress 写一次
        //    （PlayerController.reportProgressIfNeeded）。
        //    不先停的话：清完表 → 下一个 tick 又把**上一个用户**的
        //    播放进度写回本地库。
        //    真机 E2E 实测到这个残留行（article_id=art_3d14…, position=93156，
        //    updated_at 晚于清理时刻）—— 共享设备上，下一个人登录就能看到
        //    别人的续播位置。
        //
        // ② 再掐掉后台任务。它们也是"会自己把数据写回来"的东西：
        //    预下载 worker 会把上一个用户的音频重新灌进缓存（缓存刚清空又下回来），
        //    进度补传 worker 会把上一个用户的播放位置推回服务端。
        //
        // ③ 最后清库 + 清缓存。
        bestEffort("停止播放") { playerController.stop() }
        bestEffort("取消后台任务") { prefetchScheduler.cancelUserScopedWork() }

        // 再清库：下载台账 + 播放进度。一个事务，保证要么都清掉要么都没清 ——
        // 半清状态等于"下载没了但进度还在"，那正是最该避免的中间态。
        bestEffort("清空本地库") {
            database.withTransaction {
                database.downloadedAudioDao().clearAll()
                database.playbackProgressDao().clearAll()
            }
        }

        // 最后清缓存：音频字节本体。释放 SimpleCache 并删掉目录，
        // 下一个用户拿到的是一个全新的空缓存。
        bestEffort("清空音频缓存") { offlineDownloadManager.clearCache() }
    }

    /**
     * 单步失败不许连累其他步骤，也不许让登出失败。
     *
     * 但**取消必须照常往上抛**（`CancellationException` 不吞）——
     * 吞掉它等于破坏协程契约，"点了退出登录没反应"的老毛病就是这么来的。
     */
    private suspend fun bestEffort(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what 失败：${e.message}")
        }
    }

    private companion object {
        const val TAG = "LocalUserDataCleaner"
    }
}
