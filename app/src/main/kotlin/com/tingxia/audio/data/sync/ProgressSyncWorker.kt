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
