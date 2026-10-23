package com.tingxia.audio.data.local

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 本地库 DI（2026-10-02 新增）。
 *
 * `fallbackToDestructiveMigration` 要慎用 —— 它会在 schema 变化时**直接丢库**。
 * 这里刻意不用：v1 起步，等真需要迁移时写 Migration 并在测试里验证。
 * 万一将来加错，编译期 KSP 会因为缺 migration 而报错（exportSchema = true 的意义）。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * v1 → v2：加播放列表表（2026-10-23）。
     *
     * 迁移要**显式写**，不能靠 `fallbackToDestructiveMigration` ——
     * 那会在 schema 变化时把整个库丢掉：下载台账没了（用户要重下）、
     * 播放进度没了（续播位置丢）。
     *
     * 建表语句要与 [PlaylistItemEntity] 完全一致（Room 校验的是列名/类型/主键/索引），
     * 少一列都会在运行时抛 IllegalStateException。
     */
    val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `playlist_items` (
                    `article_id` TEXT NOT NULL,
                    `position` INTEGER NOT NULL,
                    `title` TEXT,
                    `audio_url` TEXT,
                    `duration_sec` INTEGER,
                    `added_at` INTEGER NOT NULL,
                    PRIMARY KEY(`article_id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_playlist_items_position`
                ON `playlist_items` (`position`)
                """.trimIndent(),
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            // 不开 destructiveMigration：静默丢数据比启动失败更难查
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides
    fun provideDownloadedAudioDao(db: AppDatabase): DownloadedAudioDao = db.downloadedAudioDao()

    @Provides
    fun providePlaybackProgressDao(db: AppDatabase): PlaybackProgressDao = db.playbackProgressDao()

    @Provides
    fun providePlaylistDao(db: AppDatabase): PlaylistDao = db.playlistDao()

    /**
     * 登出清数据用的清理器（2026-10-21）。
     *
     * 绑接口而不是直接用实现类，是为了让 [com.tingxia.audio.auth.AuthRepository]
     * 的单测能塞一个假实现进去（Room 库 / Media3 缓存 / WorkManager 都不是好 mock 的）。
     */
    @Provides
    @Singleton
    fun provideUserDataCleaner(cleaner: LocalUserDataCleaner): UserDataCleaner = cleaner
}
