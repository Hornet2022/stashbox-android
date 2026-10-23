package com.tingxia.audio.audio

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 真 MediaSession 回调（CP4.4）。
 *
 * Media3 的 [MediaSession] 会把控制器/系统下发的播放指令（play/pause/stop/seek）
 * 自动转发给其绑定的 [Player]（即服务内的 ExoPlayer），因此这里无需重写
 * onPlay/onPause 等回调，只需在 [onConnect] 中声明允许控制器使用的 player 命令。
 *
 * ## 2026-10-23：命令集合补全
 *
 * 原来只暴露 4 个命令（PLAY_PAUSE / STOP / SEEK_TO_DEFAULT_POSITION /
 * SEEK_IN_CURRENT_MEDIA_ITEM），其余全被挡在门外。后果：
 *
 * - **系统媒体面板**（Android 13+ 下拉通知栏的媒体区、锁屏）取不到
 *   时间轴与曲目信息 → 面板里是空的或只有个进度条；
 * - **耳机双击 / 切歌键**走的是 SEEK_BACK / SEEK_FORWARD / SEEK_TO_NEXT，
 *   没暴露 → 按了没反应；
 * - **变速**（App 内有倍速档位）走 SET_SPEED_AND_PITCH，没暴露 →
 *   系统面板上调不了速度。
 *
 * 注意 Media3 1.4.1 的 `ConnectionResult.DEFAULT_PLAYER_COMMANDS` 是**全部**命令
 * （`Player.Commands.Builder().addAllCommands()`），所以「不限制」并不等于
 * 「都支持」—— 但我们这里**显式列出**要用哪些，而不是一把梭：
 * 放行 CHANGE_MEDIA_ITEMS 之类本 App 并不实现的命令，只会让系统拿到一个
 * 「点了没反应」的控制项。
 *
 * ⚠️ Media3 1.4.1 的命令名是 `COMMAND_SEEK_BACK`（新版本才改名成
 * `COMMAND_SEEK_BACKWARD`）—— 照新文档写会编译不过。
 */
class TingxiaMediaSessionCallback(
    private val playback: PlayerController,
    /** 恢复请求要读落盘的「上次在听什么」—— 需要 Context。 */
    private val context: Context,
) : MediaSession.Callback {

    @SuppressLint("UnsafeOptInUsageError")
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
            // 快退 / 快进：耳机双击、切歌键、系统媒体面板的 ± 按钮。
            // 步长在 PlayerModule 里统一设成与 App 内 UI 一致（退 10s / 进 30s）。
            .add(Player.COMMAND_SEEK_BACK)
            .add(Player.COMMAND_SEEK_FORWARD)
            // 曲目信息与时间轴：系统媒体面板要靠它们画标题/进度/时长。
            // 不给 → 面板里只有一个没有上下文的进度条。
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_METADATA)
            // 上一首 / 下一首：队列进播放器（2026-10-23 批 3）后才真正有下一项，
            // 但命令先放行 —— 播放器一有下一项，系统立刻就能用。
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            // 变速：App 内有倍速档位（common/models/tts_voice.py 下发），
            // 系统面板上也该能调。
            .add(Player.COMMAND_SET_SPEED_AND_PITCH)
            .build()

        // 自定义命令必须**同时**出现在 availableSessionCommands 里 ——
        // Media3 文档明写：「custom layout 里的命令要加进控制器连接时的
        // available session commands」。漏了这一步是最隐蔽的失败：
        // 按钮画在通知上了，按下去什么都不发生。
        val availableSessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(PlaybackCommands.ACTION_SEEK_BACK, Bundle.EMPTY))
                .add(SessionCommand(PlaybackCommands.ACTION_SEEK_FORWARD, Bundle.EMPTY))
                .add(SessionCommand(PlaybackCommands.ACTION_SKIP_PREVIOUS, Bundle.EMPTY))
                .add(SessionCommand(PlaybackCommands.ACTION_SKIP_NEXT, Bundle.EMPTY))
                .add(SessionCommand(PlaybackCommands.ACTION_SLEEP_TIMER, Bundle.EMPTY))
                .build()

        return MediaSession.ConnectionResult.accept(
            availableSessionCommands,
            availablePlayerCommands,
        )
    }

    /**
     * 自定义命令派发（2026-10-23 新增）。
     *
     * 走自定义命令而不是 player 命令，是因为「上一首 / 下一首 / 睡眠定时器」
     * 不是播放器的原生能力 —— 队列在 App 层、定时器是 App 的策略。
     * 自定义命令是 Media3 官方留给这种扩展的口子。
     *
     * ⚠️ Android 13 起系统 UI 的通知**直接**把命令发给 session，
     * 所以在 session 层处理，API 级别都一致。
     */
    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            PlaybackCommands.ACTION_SEEK_BACK -> playback.seekBack()
            PlaybackCommands.ACTION_SEEK_FORWARD -> playback.seekForward()
            PlaybackCommands.ACTION_SKIP_PREVIOUS -> playback.playPrevious()
            PlaybackCommands.ACTION_SKIP_NEXT -> playback.playNext()
            PlaybackCommands.ACTION_SLEEP_TIMER -> {
                val minutes = args.getInt(PlaybackCommands.EXTRA_MINUTES, 0)
                playback.setSleepTimerMinutes(minutes)
            }
            else -> return super.onCustomCommand(session, controller, customCommand, args)
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    /**
     * 「继续播放」：Android 13+ 的系统媒体面板会记住上次在听什么，
     * 用户点「继续播放」时系统向服务发恢复请求 —— 不实现它，那个按钮是死的。
     *
     * 返回上次那一项 + 上次的播放位置。恢复后
     * [PlayerController] 的监听器会从 mediaId 读回 articleId，
     * 于是「当前是哪一篇」「完听上报到哪一篇」都还是对的。
     */
    override fun onPlaybackResumption(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val resumable = PlaybackResumption.read(context)
            ?: return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            )
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(
                listOf(resumable.toMediaItem()),
                /* startIndex = */ 0,
                resumable.positionMs,
            )
        )
    }
}
