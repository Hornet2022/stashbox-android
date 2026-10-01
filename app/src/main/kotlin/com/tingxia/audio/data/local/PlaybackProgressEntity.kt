package com.tingxia.audio.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 播放进度的**本地镜像**（2026-10-02 新增）。
 *
 * 之前进度只走网络（`PlayerController.reportProgressIfNeeded` → HTTP），
 * 读侧还包在 `runCatching` 里，失败就静默当"没进度"。于是地铁里断网重开文章
 * 必然从头播 —— 用户听到的是"我明明听到 20 分钟了，怎么又从头开始"。
 *
 * 现在是**本地优先**：读先查这张表（断网也能续播），再后台同步到服务端。
 * [syncedToServer] 标记是否已与服务端对齐，联网时补传。
 */
@Entity(
    tableName = "playback_progress",
    indices = [Index(value = ["synced_to_server"])],
)
data class PlaybackProgressEntity(
    @PrimaryKey
    @ColumnInfo(name = "article_id")
    val articleId: String,

    @ColumnInfo(name = "position_ms")
    val positionMs: Long,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    /** 听完了就不再续播（从头开始才是对的）。 */
    @ColumnInfo(name = "completed")
    val completed: Boolean = false,

    @ColumnInfo(name = "synced_to_server")
    val syncedToServer: Boolean = false,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 续播位置；听完了返回 null（调用方从头播）。 */
    val resumePositionMs: Long?
        get() = if (completed || positionMs <= 0) null else positionMs
}
