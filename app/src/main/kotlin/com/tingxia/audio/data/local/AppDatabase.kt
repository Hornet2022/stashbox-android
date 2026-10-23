package com.tingxia.audio.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 听匣本地库（2026-10-02 新增）。
 *
 * 只放**离线要用**的东西：下载台账 + 播放进度。业务数据（文章/列表/用户）
 * 仍然以服务端为准，不做全量镜像 —— 那会把「数据一致性」问题从一个库
 * 扩散到两个。
 */
@Database(
    entities = [
        DownloadedAudioEntity::class,
        PlaybackProgressEntity::class,
        PlaylistItemEntity::class,
    ],
    // v2：加播放列表表（2026-10-23）。
    // 不用 destructiveMigration —— 那会在 schema 变化时**直接丢库**，
    // 而下载台账与播放进度丢了是要用户重新下载/重听的。
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadedAudioDao(): DownloadedAudioDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        const val NAME = "tingxia.db"
    }
}
