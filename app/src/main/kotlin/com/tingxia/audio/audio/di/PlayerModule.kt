package com.tingxia.audio.audio.di

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.tingxia.audio.audio.OfflineDownloadManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 提供**共享** ExoPlayer 单例（CP4.4 修复双 ExoPlayer 架构断裂）。
 *
 * [PlayerController] 与 [com.tingxia.audio.audio.AudioPlayerService] 共用同一实例，
 * 后者将其绑定到 MediaSession，使锁屏 / 通知 / 后台统一控制真实出声的播放器。
 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    fun provideExoPlayer(
        @ApplicationContext context: Context,
        offlineDownloadManager: OfflineDownloadManager,
    ): ExoPlayer {
        val cacheFactory = offlineDownloadManager.buildCacheDataSourceFactory()
        val mediaSourceFactory = DefaultMediaSourceFactory(cacheFactory)
        return ExoPlayer.Builder(context).setMediaSourceFactory(mediaSourceFactory).build()
    }
}
