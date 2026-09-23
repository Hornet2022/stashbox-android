package com.tingxia.audio.audio

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tingxia.audio.R
import com.tingxia.audio.audio.di.AudioPlayerEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * 音频播放前台服务（CP4.4）。
 *
 * 持有 [MediaSession]，其底层 [androidx.media3.exoplayer.ExoPlayer] 来自 [PlayerModule]
 * 提供的**共享单例**——与 [PlayerController] 是同一实例。系统据此接管锁屏 / 通知控制，
 * 后台播放也由该 MediaSessionService 守护。
 *
 * CP11.0.7 P2.1: 共享 ExoPlayer 已注入 cache data source factory（见 [PlayerModule]），
 * 服务内播放同样命中本地音频缓存。
 */
@UnstableApi
class AudioPlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // 共享 ExoPlayer（与 PlayerController 同一实例），通过 Hilt EntryPoint 取到
        val app = applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(app, AudioPlayerEntryPoint::class.java)
        val player = entryPoint.exoPlayer()

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
        // 仅释放 MediaSession；共享 ExoPlayer 生命周期随 App（由 PlayerModule 提供），
        // 此处不应 release，否则会破坏 PlayerController 正在使用的同一实例。
        mediaSession?.let { session ->
            session.release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
