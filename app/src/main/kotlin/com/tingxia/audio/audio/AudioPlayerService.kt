package com.tingxia.audio.audio

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tingxia.audio.R
import com.tingxia.audio.audio.di.AudioPlayerEntryPoint

/**
 * 音频播放前台服务（CP4.4）。
 *
 * - 继承 Media3 的 [MediaSessionService]，系统据此接管锁屏 / 通知控制（CP4.5 接 UI 细节）
 * - 内含 [ExoPlayer] 实例 + [MediaSession] 实例
 * - 播放开始（playWhenReady=true 且有媒体）时，MediaSessionService 自动转前台服务并展示
 *   Media3 默认通知（DefaultMediaNotificationProvider）
 * - 生命周期：onCreate 建 player + session / onDestroy 释放
 *
 * CP11.0.7 P2.1: 通过 [AudioPlayerEntryPoint]（Hilt EntryPoint）从 Application 取到
 *   [OfflineDownloadManager]，ExoPlayer 的 RenderersFactory 注入 cache data source，
 *   服务内播放同样命中本地音频缓存。
 */
@UnstableApi
class AudioPlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // 通过 Hilt EntryPoint 从 Application 取 OfflineDownloadManager
        val app = applicationContext
        val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
            app,
            AudioPlayerEntryPoint::class.java,
        )
        val offlineDownloadManager: OfflineDownloadManager = entryPoint.offlineDownloadManager()
        val cacheFactory = offlineDownloadManager.buildCacheDataSourceFactory()

        // ExoPlayer 用 DefaultMediaSourceFactory(cacheFactory) 注入，
        // 通过 RenderersFactory 注入 cache data source path：
        // ExoPlayer.Builder + setMediaSourceFactory 不存在 1.4.1，
        // 改用 DefaultRenderersFactory.setDataSourceFactory 内部传递。
        // 实际 Media3 推荐路径：ExoPlayer 接受 RenderersFactory 参数，
        // RenderersFactory 内部的 ExtractorMediaPeriod 创建 ProgressiveMediaPeriod 时用 dataSourceFactory。
        // Media3 1.4.1 中 DataSource.Factory 通过 Renderer 内部的
        // LoadControl 注入，复杂；简化方案 = 通过 DefaultMediaSourceFactory 注入 ExoPlayer
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheFactory)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        val callback = TingxiaMediaSessionCallback()

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(callback)
            .build()

        // CP4.5: 用 Media3 自带 DefaultMediaNotificationProvider 自动处理锁屏 UI + MediaStyle 通知
        // （含 play/pause + skip previous/next + close actions），无需自定义 NotificationBuilder
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationIdProvider { _ -> NOTIFICATION_ID }
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.audio_player_channel_name)
                .build(),
        )
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_CHANNEL_ID = "stashbox_audio_playback"
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return super.onTaskRemoved(rootIntent)
        // 没有在播放 / 没有媒体时，任务被移除就直接停服务，避免后台空跑
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.let { session ->
            session.player.release()
            session.release()
            mediaSession = null
        }
        super.onDestroy()
    }
}