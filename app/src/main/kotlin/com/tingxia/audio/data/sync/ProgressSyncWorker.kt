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
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tingxia.audio.audio.OfflineDownloadManager
import com.tingxia.audio.data.repository.ProgressRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 断网攒下的播放进度补传（2026-10-02）。
 *
 * 断网时进度只落在 Room；联网后靠这个 Worker 把它推回服务端，
 * 否则「多端进度一致」会在地铁里断一次就永久丢一次。
 *
 * 由 [PendingSyncScheduler] 在网络恢复时排入，也可以在 App 启动时排一次。
 */
@HiltWorker
class ProgressSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val progressRepository: ProgressRepository,
    private val offlineDownloadManager: OfflineDownloadManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        progressRepository.syncPending()
        // 顺带校准下载台账：LRU 淘汰掉的文件，对应的行删掉
        offlineDownloadManager.reconcileWithCache()
        Result.success()
    } catch (e: Exception) {
        // 失败要重试，但不能无限重试 —— 30 分钟后放弃
        if (runAttemptCount >= 3) Result.failure() else Result.retry()
    }

    companion object {
        const val NAME = "progress-sync"
    }
}
