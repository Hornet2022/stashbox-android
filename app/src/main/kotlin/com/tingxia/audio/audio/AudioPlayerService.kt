package com.tingxia.audio.audio

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 音频播放前台服务（CP4.4）。
 *
 * - 继承 Media3 的 [MediaSessionService]，系统据此接管锁屏 / 通知控制（CP4.5 接 UI 细节）
 * - 内含 [ExoPlayer] 实例 + [MediaSession] 实例
 * - 播放开始（playWhenReady=true 且有媒体）时，MediaSessionService 自动转前台服务并展示
 *   Media3 默认通知（DefaultMediaNotificationProvider）
 * - 生命周期：onCreate 建 player + session / onDestroy 释放
 */
@UnstableApi
class AudioPlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this).build()
        val callback = TingxiaMediaSessionCallback()

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(callback)
            .build()
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
