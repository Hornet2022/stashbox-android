package com.tingxia.audio.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地已下载音频的元数据（2026-10-02 新增）。
 *
 * 音频字节本身存在 Media3 的 [androidx.media3.datasource.cache.SimpleCache]
 * 里（`<cacheDir>/audio_cache`，LRU 100MB）；这张表存的是**指针 + 业务信息**，
 * 让「哪些能离线播」在断网时也能查得到。
 *
 * 为什么必须有：SimpleCache 的 LRU 会自己淘汰文件，App 侧如果不记账，
 * 就会出现「界面显示已下载、实际文件早被淘汰、点开报网络错误」。
 */
@Entity(
    tableName = "downloaded_audio",
    indices = [Index(value = ["audio_url"], unique = true)],
)
data class DownloadedAudioEntity(
    @PrimaryKey
    @ColumnInfo(name = "article_id")
    val articleId: String,

    @ColumnInfo(name = "audio_url")
    val audioUrl: String,

    @ColumnInfo(name = "title")
    val title: String? = null,

    @ColumnInfo(name = "duration_sec")
    val durationSec: Int? = null,

    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long? = null,

    /** 音频在 SimpleCache 里的实际字节数，用来判断是否下完。 */
    @ColumnInfo(name = "cached_bytes")
    val cachedBytes: Long = 0L,

    /** 服务端声明的总长度；cachedBytes >= 它才算下完。 */
    @ColumnInfo(name = "expected_bytes")
    val expectedBytes: Long = 0L,

    /** 用户手动点过「下载」，与「播过一遍被动缓存」区分开。 */
    @ColumnInfo(name = "pinned")
    val pinned: Boolean = false,

    @ColumnInfo(name = "cached_at")
    val cachedAt: Long = System.currentTimeMillis(),
) {
    val isComplete: Boolean
        get() = expectedBytes > 0 && cachedBytes >= expectedBytes
}
