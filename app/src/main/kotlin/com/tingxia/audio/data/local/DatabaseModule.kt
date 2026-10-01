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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            // 不开 destructiveMigration：静默丢数据比启动失败更难查
            .build()

    @Provides
    fun provideDownloadedAudioDao(db: AppDatabase): DownloadedAudioDao = db.downloadedAudioDao()

    @Provides
    fun providePlaybackProgressDao(db: AppDatabase): PlaybackProgressDao = db.playbackProgressDao()
}
