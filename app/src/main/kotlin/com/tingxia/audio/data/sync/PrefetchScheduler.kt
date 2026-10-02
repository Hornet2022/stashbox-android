package com.tingxia.audio.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.hilt.work.HiltWorker
import com.tingxia.audio.audio.OfflineDownloadManager
import com.tingxia.audio.data.local.DownloadedAudioDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 预下载一集音频（2026-10-02）。
 *
 * 方案 §1 闭环 3 要求「通勤时段自动预加载下一篇文章」。用 WorkManager 而不是
 * `Handler.postDelayed` / 前台服务：App 被系统杀掉后任务仍在，Doze 模式下
 * 也能被唤醒，且不用自己处理并发去重。
 */
@HiltWorker
class AudioPrefetchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val offlineDownloadManager: OfflineDownloadManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val articleId = inputData.getString(KEY_ARTICLE_ID) ?: return Result.failure()
        val audioUrl = inputData.getString(KEY_AUDIO_URL) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE)
        val durationSec = inputData.getInt(KEY_DURATION_SEC, 0).takeIf { it > 0 }

        val ok = offlineDownloadManager.download(articleId, audioUrl, title, durationSec)
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        const val NAME = "audio-prefetch"
        const val KEY_ARTICLE_ID = "article_id"
        const val KEY_AUDIO_URL = "audio_url"
        const val KEY_TITLE = "title"
        const val KEY_DURATION_SEC = "duration_sec"
    }
}

/**
 * 预加载 / 补传的调度入口（2026-10-02）。
 */
@Singleton
class PrefetchScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * 预下载一集。要求有网络才跑，避免在地铁里空转。
     *
     * 同一篇重复调用不会重复下载（Work 的唯一名 + KEEP 策略）。
     */
    fun prefetchAudio(articleId: String, audioUrl: String, title: String? = null, durationSec: Int? = null) {
        val req = OneTimeWorkRequestBuilder<AudioPrefetchWorker>()
            .setInputData(
                androidx.work.Data.Builder()
                    .putString(AudioPrefetchWorker.KEY_ARTICLE_ID, articleId)
                    .putString(AudioPrefetchWorker.KEY_AUDIO_URL, audioUrl)
                    .putString(AudioPrefetchWorker.KEY_TITLE, title)
                    .putInt(AudioPrefetchWorker.KEY_DURATION_SEC, durationSec ?: 0)
                    .build()
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(AudioPrefetchWorker.NAME)
            .build()

        workManager.enqueueUniqueWork(
            "prefetch-$articleId",
            ExistingWorkPolicy.KEEP,
            req,
        )
    }

    /**
     * 排一次「补传断网期间攒的进度 + 校准下载台账」。
     *
     * 周期性排一份（每小时）作兜底，App 启动时再排一次即时的。
     */
    /**
     * 盯一篇刚剪藏的文章，蒸馏完发本地通知。
     *
     * 这是「文章好了」能到达用户的**唯一**通路：没有推送通道，`push_notifications`
     * 表 0 行，纯靠 ViewModel 轮询的话用户切走 app 就收不到了。
     *
     * 唯一名带 articleId + KEEP：同一篇重复剪藏不会排多份。
     */
    fun watchDistill(articleId: String) {
        val req = OneTimeWorkRequestBuilder<DistillReadyWorker>()
            .setInputData(
                androidx.work.Data.Builder()
                    .putString(DistillReadyWorker.KEY_ARTICLE_ID, articleId)
                    .build()
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(DistillReadyWorker.NAME)
            .build()

        workManager.enqueueUniqueWork(
            "distill-ready-$articleId",
            ExistingWorkPolicy.KEEP,
            req,
        )
    }

    fun scheduleProgressSync() {
        val req = PeriodicWorkRequestBuilder<ProgressSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(ProgressSyncWorker.NAME)
            .build()

        workManager.enqueueUniquePeriodicWork(
            ProgressSyncWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }

    /** 立刻跑一次补传（App 冷启动时调）。 */
    fun syncNow() {
        val req = OneTimeWorkRequestBuilder<ProgressSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(ProgressSyncWorker.NAME)
            .build()

        workManager.enqueueUniqueWork(
            "${ProgressSyncWorker.NAME}-now",
            ExistingWorkPolicy.REPLACE,
            req,
        )
    }
}
