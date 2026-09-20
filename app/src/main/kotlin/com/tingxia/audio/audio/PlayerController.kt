package com.tingxia.audio.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import android.net.Uri
import com.tingxia.audio.data.remote.ProgressApi
import com.tingxia.audio.data.remote.ProgressUpdateRequest
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
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
 *
 * CP11.0.1: 断点续听 — 每 10 秒上报 progress（仅播放中）。
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

    // CP11.0.1: 断点续听进度上报
    private var progressApi: ProgressApi? = null
    private var currentArticleId: String? = null
    private var lastReportTimeMs = 0L
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            reportProgressIfNeeded()
            progressHandler.postDelayed(this, PROGRESS_REPORT_INTERVAL_MS)
        }
    }

    /** 设置 ProgressApi（由 Hilt 注入，ArticleDetailViewModel 调用）。 */
    fun setProgressApi(api: ProgressApi) {
        progressApi = api
    }

    /** 设置当前播放的文章 ID（开始播放时由调用方设置）。 */
    fun setCurrentArticleId(articleId: String?) {
        currentArticleId = articleId
    }

    private fun reportProgressIfNeeded() {
        if (_state.value != PlaybackState.PLAYING) return
        val articleId = currentArticleId ?: return
        val api = progressApi ?: return

        val positionMs = _position.value
        val positionSec = (positionMs / 1000).toInt()
        val now = System.currentTimeMillis()

        // 避免重复上报（只在上次上报后过了足够时间才报）
        if (now - lastReportTimeMs < PROGRESS_REPORT_INTERVAL_MS) return

        lastReportTimeMs = now
        Log.i("ProgressApi", "posted position=$positionSec articleId=$articleId")

        // IO 线程执行网络请求
        Thread {
            try {
                runBlocking {
                    api.updateProgress(
                        articleId,
                        ProgressUpdateRequest(position_sec = positionSec, total_sec = null)
                    )
                }
            } catch (e: Exception) {
                Log.w("ProgressApi", "failed to report progress: ${e.message}")
            }
        }.start()
    }

    /** 惰性创建底层 ExoPlayer（仅一次）。需在主线程调用。 */
    fun initialize() {
        if (player != null) return
        player = ExoPlayer.Builder(appContext).build()
    }

    /**
     * 播放指定音频直链，并携带锁屏/通知所需元数据（CP4.5）。
     * 若尚未 [initialize]，仅更新状态机（不真正出声）——UI 仍可反映"播放中"。
     *
     * @param audioUrl 音频直链
     * @param title    锁屏/通知标题（文章标题）；默认空串，兼容无标题的手动起播
     * @param author   锁屏/通知副标题（文章来源/作者），可空
     * @param coverUrl 锁屏封面图 URL，可空（CP4.7 接入封面字段）
     */
    fun play(
        audioUrl: String,
        title: String = "",
        author: String? = null,
        coverUrl: String? = null,
    ) {
        val p = player
        if (p == null) {
            _state.value = PlaybackState.PLAYING
            return
        }
        _state.value = PlaybackState.PLAYING
        val mediaItem = MediaItem.Builder()
            .setUri(audioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(author)
                    .setArtworkUri(coverUrl?.let(Uri::parse))
                    .build(),
            )
            .build()
        p.setMediaItem(mediaItem)
        p.prepare()
        p.play()
        startPolling()
        startProgressReporting()
    }

    fun pause() {
        _state.value = PlaybackState.PAUSED
        player?.pause()
    }

    fun stop() {
        _state.value = PlaybackState.STOPPED
        player?.stop()
        stopPolling()
        stopProgressReporting()
        _position.value = 0L
        _duration.value = 0L
    }

    fun seekTo(positionMs: Long) {
        _position.value = positionMs
        player?.seekTo(positionMs)
    }

    fun release() {
        stopPolling()
        stopProgressReporting()
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

    private fun startProgressReporting() {
        lastReportTimeMs = 0L
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.postDelayed(progressRunnable, PROGRESS_REPORT_INTERVAL_MS)
    }

    private fun stopProgressReporting() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    companion object {
        private const val POLL_INTERVAL_MS = 500L
        private const val PROGRESS_REPORT_INTERVAL_MS = 10_000L
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
