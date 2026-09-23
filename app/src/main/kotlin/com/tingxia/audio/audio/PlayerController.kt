package com.tingxia.audio.audio

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import com.tingxia.audio.data.remote.ProgressApi
import com.tingxia.audio.data.remote.ProgressUpdateRequest
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放器状态机。
 */
enum class PlaybackState { IDLE, PLAYING, PAUSED, STOPPED }

/**
 * ExoPlayer 封装 + 真实播放状态（CP4.4）。
 *
 * - 单例（[Singleton]），由 Hilt 通过 [PlayerModule] 注入**共享** ExoPlayer 实例。
 * - 同一 ExoPlayer 也被 [AudioPlayerService]（MediaSessionService）持有并绑定 MediaSession，
 *   因此锁屏 / 通知 / 后台播放统一控制这一份播放器（修复双 ExoPlayer 架构断裂）。
 * - 暴露 [StateFlow] 给 Compose UI：[state] 播放状态、[position] / [duration] 进度，
 *   [currentTitle] / [currentAuthor] / [currentAudioUrl] 当前曲目元数据（供全屏/迷你条展示）。
 * - 命令：[play] / [pause] / [stop] / [seekTo] / [resume]，转发给内部 [ExoPlayer]。
 * - 每 500ms 轮询一次 [ExoPlayer.getCurrentPosition] 更新 [position] / [duration]。
 *
 * UI（[com.tingxia.audio.ui.components.AudioPlayerBar]）通过 [PlayerControllerEntryPoint]
 * 在 Compose 内取到本单例并 collect 其 StateFlow。
 *
 * CP11.0.1: 断点续听 — 每 10 秒上报 progress（仅播放中）。
 */
@Singleton
class PlayerController @Inject constructor(
    private val player: ExoPlayer,
) {

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _currentTitle = MutableStateFlow("")
    val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()

    private val _currentAuthor = MutableStateFlow("")
    val currentAuthor: StateFlow<String> = _currentAuthor.asStateFlow()

    private val _currentAudioUrl = MutableStateFlow("")
    val currentAudioUrl: StateFlow<String> = _currentAudioUrl.asStateFlow()

    private val positionHandler = Handler(Looper.getMainLooper())
    private val positionRunnable = object : Runnable {
        override fun run() {
            player.let { p ->
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
    private val progressScope = CoroutineScope(Dispatchers.IO + Job())
    private var progressJob: Job? = null

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

        // Dispatchers.IO 协程执行网络请求（不复用 Thread，每 10s 启动一个轻量协程）
        progressScope.launch {
            try {
                api.updateProgress(
                    articleId,
                    ProgressUpdateRequest(position_sec = positionSec, total_sec = null)
                )
            } catch (e: Exception) {
                Log.w("ProgressApi", "failed to report progress: ${e.message}")
            }
        }
    }

    /**
     * 播放指定音频直链，并携带锁屏/通知所需元数据（CP4.5）。
     */
    fun play(
        audioUrl: String,
        title: String = "",
        author: String? = null,
        coverUrl: String? = null,
    ) {
        _state.value = PlaybackState.PLAYING
        _currentTitle.value = title
        _currentAuthor.value = author ?: ""
        _currentAudioUrl.value = audioUrl
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
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        startPolling()
        startProgressReporting()
    }

    /**
     * 恢复播放当前曲目（不重建 MediaItem，避免从头开始）。
     * 仅当处于 PAUSED 时有效。
     */
    fun resume() {
        if (_state.value != PlaybackState.PAUSED) return
        _state.value = PlaybackState.PLAYING
        player.play()
        startPolling()
        startProgressReporting()
    }

    fun pause() {
        _state.value = PlaybackState.PAUSED
        player.pause()
    }

    fun stop() {
        _state.value = PlaybackState.STOPPED
        player.stop()
        stopPolling()
        stopProgressReporting()
        _position.value = 0L
        _duration.value = 0L
    }

    fun seekTo(positionMs: Long) {
        _position.value = positionMs
        player.seekTo(positionMs)
    }

    /**
     * 复位播放器状态流（注意：共享 ExoPlayer 生命周期随 App，此处不 release 它，
     * 仅复位状态，避免误杀被 MediaSession 绑定的同一实例）。
     */
    fun release() {
        stopPolling()
        stopProgressReporting()
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
        progressJob?.cancel()
        lastReportTimeMs = 0L
        progressJob = progressScope.launch {
            while (true) {
                delay(PROGRESS_REPORT_INTERVAL_MS)
                reportProgressIfNeeded()
            }
        }
    }

    private fun stopProgressReporting() {
        progressJob?.cancel()
        progressJob = null
    }

    companion object {
        private const val TAG = "PlayerController"
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
