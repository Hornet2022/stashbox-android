package com.tingxia.audio.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 内存版播放进度 DAO（2026-10-02）。
 *
 * 用途：
 * - Compose 预览 / 单测（不想在测试里拖一个 Room 文件出来）；
 * - Room 初始化失败时的降级 —— 进度记不住总比崩掉好。
 *
 * 数据不跨进程、不持久化，**不能**当生产实现用。
 */
class InMemoryPlaybackProgressDao : PlaybackProgressDao {

    private val rows = mutableMapOf<String, PlaybackProgressEntity>()

    override suspend fun upsert(entity: PlaybackProgressEntity) {
        rows[entity.articleId] = entity
    }

    override suspend fun byArticle(articleId: String): PlaybackProgressEntity? = rows[articleId]

    override fun observeByArticle(articleId: String): Flow<PlaybackProgressEntity?> =
        flowOf(rows[articleId])

    override suspend fun unsynced(): List<PlaybackProgressEntity> =
        rows.values.filterNot { it.syncedToServer }

    override suspend fun markSynced(articleId: String) {
        rows[articleId]?.let { rows[articleId] = it.copy(syncedToServer = true) }
    }

    override suspend fun delete(articleId: String) {
        rows.remove(articleId)
    }
}
