package com.tingxia.audio.data.repository

import com.tingxia.audio.data.local.PlaybackProgressDao
import com.tingxia.audio.data.local.PlaybackProgressEntity
import com.tingxia.audio.data.remote.ProgressApi
import com.tingxia.audio.data.remote.ProgressUpdateRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放进度仓库（2026-10-02 重写为本地优先）。
 *
 * ## 之前的问题
 *
 * 读和写都只走网络：
 * - 写：`PlayerController` 每 10s POST 一次，异常只 `Log.w` 静默丢弃；
 * - 读：`ArticleDetailViewModel` 包在 `runCatching` 里，失败就当"没有进度"，
 *   接着 `seekTo` 整段跳过 → **断网重开必然从头播**。
 *
 * 通勤场景 80% 在地铁/电梯无网弱网，这不是边缘情况，是主场景。
 *
 * ## 现在的口径
 *
 * - **读**：先查 Room（断网可用）；本地没有才回源服务端，成功后回填本地。
 * - **写**：先落 Room（立即生效），再后台同步服务端；同步失败就留着
 *   `synced_to_server = 0`，由 [syncPending] 在下次联网时补传。
 *
 * 这样「断网续播」和「多端进度一致」两个诉求不再互相打架。
 */
@Singleton
open class ProgressRepository @Inject constructor(
    private val api: ProgressApi,
    private val dao: PlaybackProgressDao,
) {

    /**
     * 记进度。**先本地后网络** —— 本地写是廉价的同步操作，网络失败不丢数据。
     */
    open suspend fun saveLocalProgress(
        articleId: String,
        positionMs: Long,
        durationMs: Long = 0L,
        completed: Boolean = false,
    ) {
        dao.upsert(
            PlaybackProgressEntity(
                articleId = articleId,
                positionMs = positionMs,
                durationMs = durationMs,
                completed = completed,
                syncedToServer = false,
            )
        )
    }

    /**
     * 推进度到服务端（保留给需要服务端立即可见的场景，比如手动重试）。
     * 本地已先写好，这里只负责补同步标记。
     */
    open suspend fun updateProgress(articleId: String, positionSec: Int, totalSec: Int? = null) {
        api.updateProgress(articleId, ProgressUpdateRequest(position_sec = positionSec, total_sec = totalSec))
        dao.markSynced(articleId)
    }

    /** 仅打同步标记（PlayerController 已经成功 POST 之后调）。 */
    open suspend fun markSyncedLocally(articleId: String) {
        dao.markSynced(articleId)
    }

    /**
     * 取续播位置（毫秒）。本地优先，本地没有才回源并回填。
     *
     * 听完了 / 位置为 0 一律返回 null —— 调用方从头播才是对的。
     */
    open suspend fun getResumePositionMs(articleId: String): Long? {
        dao.byArticle(articleId)?.let { return it.resumePositionMs }

        // 本地没有：回源一次，成功后写回本地
        return try {
            val remote = api.getProgress(articleId)
            val posSec = remote.position_sec
            if (posSec != null && posSec > 0) {
                dao.upsert(
                    PlaybackProgressEntity(
                        articleId = articleId,
                        positionMs = posSec.toLong() * 1000,
                        syncedToServer = true,
                    )
                )
                posSec.toLong() * 1000
            } else {
                null
            }
        } catch (e: Exception) {
            // 回源失败 = 没有进度。返回 null 从头播，不抛给 UI。
            null
        }
    }

    /**
     * 把断网期间攒下的进度补传给服务端。
     *
     * 由 [com.tingxia.audio.data.repository.PendingSyncScheduler] 在联网时触发；
     * 单条失败不中断其余条目。
     */
    open suspend fun syncPending(): Int {
        val pending = dao.unsynced()
        var ok = 0
        for (row in pending) {
            try {
                api.updateProgress(
                    row.articleId,
                    ProgressUpdateRequest(
                        position_sec = (row.positionMs / 1000).toInt(),
                        total_sec = if (row.durationMs > 0) (row.durationMs / 1000).toInt() else null,
                    ),
                )
                dao.markSynced(row.articleId)
                ok++
            } catch (e: Exception) {
                // 这条留着下次再试
            }
        }
        return ok
    }

    /** 兼容旧调用点：只读远端的原始响应。 */
    open suspend fun getProgress(articleId: String) = api.getProgress(articleId)
}
