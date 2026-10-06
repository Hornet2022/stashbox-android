// Media3 把大量播放/缓存 API 标为 @UnstableApi。
// 这些文件直接使用 ExoPlayer / SimpleCache / DownloadManager 等 unstable 声明，
// 必须显式 opt-in，否则 :app:lintDebug 报 UnsafeOptInUsageError 直接让 CI 失败。
//
// 注意用的是 **androidx.annotation.OptIn** 而不是 kotlin.OptIn：Media3 的
// UnstableApi 标的是 androidx 的 @RequiresOptIn，lint 认的是前者；写 Kotlin 的
// @OptIn 只会让 lint 在这行本身上再报一次（实测错误数 16 → 22）。
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
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
