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
    entities = [DownloadedAudioEntity::class, PlaybackProgressEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadedAudioDao(): DownloadedAudioDao
    abstract fun playbackProgressDao(): PlaybackProgressDao

    companion object {
        const val NAME = "tingxia.db"
    }
}
