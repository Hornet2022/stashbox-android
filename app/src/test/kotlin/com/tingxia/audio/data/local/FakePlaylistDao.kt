package com.tingxia.audio.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存版的 [PlaylistDao]（单测用）。
 *
 * Room 在 JVM 单测里跑不起来（要 Android 运行时），而播放列表的
 * **顺序逻辑**恰恰是最该被测的部分 —— 稠密化、上下移动、删除后重排。
 * 这些都不需要真数据库，用一个内存表就能测得更透。
 */
class FakePlaylistDao : PlaylistDao {

    private val rows = MutableStateFlow<List<PlaylistItemEntity>>(emptyList())

    /** 观察到的所有条目（按 position 排序）。 */
    val items: List<PlaylistItemEntity> get() = rows.value

    override fun observeAll(): Flow<List<PlaylistItemEntity>> = rows.map { it.sortedBy { r -> r.position } }

    override suspend fun all(): List<PlaylistItemEntity> = rows.value.sortedBy { it.position }

    override suspend fun byArticle(articleId: String): PlaylistItemEntity? =
        rows.value.firstOrNull { it.articleId == articleId }

    override suspend fun count(): Int = rows.value.size

    override suspend fun maxPosition(): Int =
        rows.value.maxOfOrNull { it.position } ?: -1

    override suspend fun upsert(entity: PlaylistItemEntity) {
        rows.value = rows.value.filterNot { it.articleId == entity.articleId } + entity
    }

    override suspend fun updatePosition(articleId: String, position: Int) {
        rows.value = rows.value.map {
            if (it.articleId == articleId) it.copy(position = position) else it
        }
    }

    override suspend fun delete(articleId: String) {
        rows.value = rows.value.filterNot { it.articleId == articleId }
    }

    override suspend fun clearAll() {
        rows.value = emptyList()
    }
}
