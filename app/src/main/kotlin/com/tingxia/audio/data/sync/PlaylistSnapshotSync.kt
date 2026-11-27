package com.tingxia.audio.data.sync

import com.tingxia.audio.data.model.Article

/**
 * 蒸馏完成 → **把音频地址写回播放列表快照**的判定与执行（C3）。
 *
 * ## 为什么要抽出来
 *
 * Worker 本身要 Android `Context` 与 `WorkerParameters`，单测里造起来很重
 * （要 Robolectric + work-testing，见 [SubscriptionNotifyLogic] 的同款理由）。
 * 而这段逻辑最容易出错的恰恰是**「什么时候该写、什么时候不该写」**：
 * 写多了 ⇒ 幽灵条目；写少了 ⇒ 用户永远看到"没有音频"。
 *
 * ⇒ 抽成不依赖 Android 的逻辑单元，用普通 JUnit 测。
 *
 * ## 它解决的是什么问题
 *
 * 用户往往在**蒸馏还没好**时就把文章加进播放列表 ⇒ 那一刻存的快照是空的。
 * 而 `PlaylistViewModel.backfillMissingMetadata` 只在**本机列表数据变化**时触发
 * （Room emit）；"服务端蒸馏完成"**不写本机表** ⇒ observer 不 emit ⇒ 补齐逻辑永远不跑
 * ⇒ 播放列表那条永远是空快照 ⇒ 用户点了才发现能播（还要手动再点一次）。
 *
 * ⇒ 在 Worker 判定 ready 的那一刻（它**手里已经**有 `article.audioUrl`）把地址写回去，
 * 零新增网络请求。
 */
object PlaylistSnapshotSync {

    /**
     * 把这篇的音频地址写回播放列表快照（若它在列表里）。
     *
     * @param article 已就绪的文章（`audioUrl` / `durationSec` 都在）
     * @param inPlaylist 判据：该文是否在用户的播放列表里
     * @param write 执行：写回快照（与 `PlaylistRepository.refreshUrl` 同参）
     *
     * @return 是否真的写了（false = 没写：不在列表里 / 地址为空 / 写失败）
     */
    suspend fun sync(
        article: Article,
        inPlaylist: suspend (String) -> Boolean,
        write: suspend (articleId: String, audioUrl: String?, title: String?, durationSec: Int?) -> Unit,
    ): Boolean {
        val audioUrl = article.audioUrl
        // 地址为空 ⇒ 写了也等于没写，还会让列表继续显示"没有音频" ⇒ 不写。
        if (audioUrl.isNullOrBlank()) return false
        // 不在播放列表里 ⇒ 不制造幽灵条目。
        if (!inPlaylist(article.id)) return false
        write(article.id, audioUrl, article.title, article.durationSec)
        return true
    }
}
