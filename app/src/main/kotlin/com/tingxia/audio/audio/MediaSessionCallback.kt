package com.tingxia.audio.audio

import androidx.media3.common.Player
import androidx.media3.session.MediaSession

/**
 * 真 MediaSession 回调（CP4.4）。
 *
 * Media3 的 [MediaSession] 会把控制器/系统下发的播放指令（play/pause/stop/seek）
 * 自动转发给其绑定的 [Player]（即服务内的 ExoPlayer），因此这里无需重写
 * onPlay/onPause 等回调，只需在 [onConnect] 中声明允许控制器使用的 player 命令。
 */
class TingxiaMediaSessionCallback : MediaSession.Callback {

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        // 暴露给控制器 / 系统的播放控制命令
        val availablePlayerCommands = Player.Commands.EMPTY.buildUpon()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .build()

        return MediaSession.ConnectionResult.accept(
            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
            availablePlayerCommands,
        )
    }
}
