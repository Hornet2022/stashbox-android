package com.tingxia.audio.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.repository.ArticleRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay

/**
 * 盯一篇刚剪藏的文章，等它蒸馏完就发本地通知。
 *
 * ## 为什么要有这个 Worker
 *
 * 原来的实现在 [com.tingxia.audio.ui.capture.CaptureViewModel] 里：3 秒轮询、
 * **30 次就放弃（90 秒）**，超时后 UI 打「蒸馏超时（>90s）」。
 *
 * 而实测一篇文章的真实耗时是 **11 分 40 秒**（LLM 改写 14s + TTS 分 7 段合成 11 分钟，
 * 华为 JEF-AN20 剪藏少数派文章，2026-10-02）。也就是说那个 90 秒不是「异常兜底」，
 * 是**每一次剪藏都会走到的正常路径** —— 用户 100% 会看到「超时」。
 *
 * 更致命的是**完成时无人告知**：`push_notifications` 表 0 行、全仓无任何推送通道代码。
 * 所以内容 10 分钟后真的 ready 了，用户也永远不知道，只能自己想起来再打开 app。
 *
 * ## 为什么用 WorkManager 而不是把 ViewModel 的轮询拉长
 *
 * 90 秒这个数字是错的，但改成「拉长轮询」只是把同一个错误推迟：用户把 app 切走、
 * 进程被回收，ViewModel 的协程就没了，通知照样发不出来。
 * WorkManager 的任务能跨进程存活，这才是「完成时能触达用户」的前提。
 *
 * 轮询节奏刻意**前密后疏**：开头 5 秒一次（大部分文章这时候就好了，体感最好），
 * 之后退到 20 秒。12 分钟按前密后疏算大约 50 次请求，不是 240 次。
 */
@HiltWorker
class DistillReadyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val articleRepository: ArticleRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val articleId = inputData.getString(KEY_ARTICLE_ID) ?: return Result.failure()
        val startedAt = System.currentTimeMillis()
        var lastTitle: String? = null
        var waited = 0L

        while (System.currentTimeMillis() - startedAt < MAX_WAIT_MS) {
            val interval = if (waited < FAST_PHASE_MS) FAST_INTERVAL_MS else SLOW_INTERVAL_MS
            delay(interval)
            waited += interval

            val article = try {
                articleRepository.getArticle(articleId)
            } catch (e: Exception) {
                // 单次网络抖动不该让整条通知链路断掉，继续等。
                // 只有超过总时长才由 while 条件收口。
                continue
            }
            lastTitle = article.title

            when (article.status) {
                DistillStatus.READY, DistillStatus.LISTENED -> {
                    DistillNotifier.notifyReady(
                        applicationContext, articleId, article.title, article.durationSec,
                    )
                    return Result.success()
                }

                DistillStatus.FAILED -> {
                    DistillNotifier.notifyFailed(applicationContext, articleId, lastTitle)
                    return Result.success()
                }

                else -> Unit  // pending / distilling，继续等
            }
        }

        // 走到这里是真超时了（默认 45 分钟，远超实测的 12 分钟，是给慢机器留的余量）。
        // 不发「失败」通知 —— 服务端可能只是慢，谎报失败比不报更糟。
        return Result.success()
    }

    companion object {
        const val NAME = "distill-ready"
        const val KEY_ARTICLE_ID = "article_id"

        /** 45 分钟。实测 12 分钟，这是 3.75 倍余量。 */
        private const val MAX_WAIT_MS = 45 * 60 * 1000L

        /** 前 2 分钟 5 秒一次 —— 大部分文章在这个窗口内就绪。 */
        private const val FAST_PHASE_MS = 2 * 60 * 1000L
        private const val FAST_INTERVAL_MS = 5_000L
        private const val SLOW_INTERVAL_MS = 20_000L
    }
}
