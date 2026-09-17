package com.tingxia.audio.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Singleton

/**
 * 播放器状态机。
 */
enum class PlaybackState { IDLE, PLAYING, PAUSED, STOPPED }

/**
 * ExoPlayer 封装 + 真实播放状态（CP4.4）。
 *
 * - 单例（[Singleton]），由 Hilt 通过 [AppModule] 提供
 * - 暴露 [StateFlow] 给 Compose UI：[state] 播放状态、[position] / [duration] 进度
 * - 命令：[play] / [pause] / [stop] / [seekTo]，转发给内部 [ExoPlayer]
 * - 每 500ms 轮询一次 [ExoPlayer.getCurrentPosition] 更新 [position] / [duration]
 *
 * UI（[com.tingxia.audio.ui.components.AudioPlayerBar]）通过 [PlayerControllerEntryPoint]
 * 在 Compose 内取到本单例并 collect 其 StateFlow。
 */
@Singleton
class PlayerController(context: Context) {

    private val appContext: Context = context.applicationContext

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private var player: ExoPlayer? = null

    private val positionHandler = Handler(Looper.getMainLooper())
    private val positionRunnable = object : Runnable {
        override fun run() {
            player?.let { p ->
                _position.value = p.currentPosition.coerceAtLeast(0L)
                _duration.value = if (p.duration == C.TIME_UNSET) 0L else p.duration
            }
            positionHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /** 惰性创建底层 ExoPlayer（仅一次）。需在主线程调用。 */
    fun initialize() {
        if (player != null) return
        player = ExoPlayer.Builder(appContext).build()
    }

    /**
     * 播放指定音频直链。
     * 若尚未 [initialize]，仅更新状态机（不真正出声）——UI 仍可反映“播放中”。
     */
    fun play(audioUrl: String) {
        val p = player
        if (p == null) {
            _state.value = PlaybackState.PLAYING
            return
        }
        _state.value = PlaybackState.PLAYING
        p.setMediaItem(MediaItem.fromUri(audioUrl))
        p.prepare()
        p.play()
        startPolling()
    }

    fun pause() {
        _state.value = PlaybackState.PAUSED
        player?.pause()
    }

    fun stop() {
        _state.value = PlaybackState.STOPPED
        player?.stop()
        stopPolling()
        _position.value = 0L
        _duration.value = 0L
    }

    fun seekTo(positionMs: Long) {
        _position.value = positionMs
        player?.seekTo(positionMs)
    }

    fun release() {
        stopPolling()
        player?.release()
        player = null
        _state.value = PlaybackState.IDLE
        _position.value = 0L
        _duration.value = 0L
    }

    private fun startPolling() {
        positionHandler.removeCallbacks(positionRunnable)
        positionHandler.postDelayed(positionRunnable, POLL_INTERVAL_MS)
    }

    private fun stopPolling() {
        positionHandler.removeCallbacks(positionRunnable)
    }

    companion object {
        private const val POLL_INTERVAL_MS = 500L
    }
}

/**
 * Hilt 入口点：允许 Compose（非 Hilt 组件）取到 [PlayerController] 单例。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlayerControllerEntryPoint {
    fun playerController(): PlayerController
}
