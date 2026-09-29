package com.tingxia.audio.audio

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    /**
     * 当前正在播放的文章 id —— 完听判定的必要条件。
     *
     * 真机踩过：`currentArticleId` 是私有裸字段、靠调用方在 [play] 之前**另外**调
     * [setCurrentArticleId] 赋值，而全项目有 4 个 `play()` 调用点只有 2 个记得调。
     * 漏掉的后果不是报错而是静默失灵：
     * - 为 null → [checkListenCompletion] 第一行就 return，`listenCompleted` 永不
     *   发射 → §2.2 listen-complete 不上报 → **§2.6 评分卡永不自动弹**
     * - 为**上一首的残留值** → 完听被报到错误 article_id 上
     *
     * 所以现在由 [play] 自己接管（参数化 articleId），并暴露成 StateFlow 让
     * 全屏播放器能把它回传（那条路径只是重建已加载的 MediaItem，不该清空）。
     */
    private val _currentArticleId = MutableStateFlow<String?>(null)
    val currentArticleId: StateFlow<String?> = _currentArticleId.asStateFlow()

    // CP3.7.0: 完听事件流（ExoPlayer.STATE_ENDED / 进度 ≥ 90%）
    // VM 收集后触发 §2.2 listen-complete + 弹 §2.6 评分卡
    private val _listenCompleted = MutableStateFlow<Long?>(null)
    val listenCompleted: SharedFlow<Long?> = _listenCompleted.asStateFlow()

    // CP3.7.0: 当前 variant 码率（kbps，128 默认；§3 多码率协商后由 VM 更新）
    private val _currentBitrate = MutableStateFlow(128)
    val currentBitrate: StateFlow<Int> = _currentBitrate.asStateFlow()

    private val positionHandler = Handler(Looper.getMainLooper())
    private val positionRunnable = object : Runnable {
        override fun run() {
            player.let { p ->
                _position.value = p.currentPosition.coerceAtLeast(0L)
                _duration.value = if (p.duration == C.TIME_UNSET) 0L else p.duration
            }
            // CP3.7.0: 完听判定 —— ExoPlayer STATE_ENDED（已到末尾）
            // 或进度 ≥ 90% 视为完听（手动拖到末尾也算）
            checkListenCompletion()
            positionHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /**
     * 判定是否完听：STATE_ENDED 或 position ≥ 90% duration。
     * 一篇只触发一次（用 [listenCompletionFired] 守门）。
     */
    private var listenCompletionFired = false
    private fun checkListenCompletion() {
        if (listenCompletionFired) return
        if (_currentArticleId.value == null) return
        val pos = _position.value
        val dur = _duration.value
        val ended = player.playbackState == Player.STATE_ENDED
        val reached = dur > 0L && pos.toFloat() / dur.toFloat() >= LISTEN_COMPLETE_RATIO
        if (ended || reached) {
            listenCompletionFired = true
            _listenCompleted.value = System.currentTimeMillis()
            Log.i(TAG, "listen completed: article=${_currentArticleId.value}, ratio=${if (dur > 0L) pos.toFloat() / dur else 0f}")
        }
    }

    /** 重置完听标记（切歌时调） */
    private fun resetListenCompletionFlag() {
        listenCompletionFired = false
        _listenCompleted.value = null
    }

    // CP11.0.1: 断点续听进度上报
    private var progressApi: ProgressApi? = null
    private var lastReportTimeMs = 0L
    private val progressScope = CoroutineScope(Dispatchers.IO + Job())
    private var progressJob: Job? = null

    /** 设置 ProgressApi（由 Hilt 注入，ArticleDetailViewModel 调用）。 */
    fun setProgressApi(api: ProgressApi) {
        progressApi = api
    }

    /**
     * 单独设置当前文章 id。
     *
     * 新代码请直接调 [play] 的 `articleId` 参数 —— 单独调这里容易漏，
     * 漏了就是完听判定静默失灵（见 [currentArticleId] 的说明）。
     * 保留它只是为了兼容既有调用点。
     */
    fun setCurrentArticleId(articleId: String?) {
        _currentArticleId.value = articleId
    }

    private fun reportProgressIfNeeded() {
        if (_state.value != PlaybackState.PLAYING) return
        val articleId = _currentArticleId.value ?: return
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
     *
     * @param articleId 当前文章 id。**完听判定的必要条件** —— 不传等于告诉播放器
     *   "这次播放不属于任何文章"，于是 listen-complete 不上报、听感评分卡不弹。
     *   只有当确实要重放同一篇文章（换档/重建 MediaItem）时才传 null 沿用旧值。
     */
    fun play(
        audioUrl: String,
        title: String = "",
        author: String? = null,
        coverUrl: String? = null,
        articleId: String? = null,
    ) {
        if (articleId != null) {
            _currentArticleId.value = articleId
        }
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
        resetListenCompletionFlag()
        startPolling()
        startProgressReporting()
    }

    /**
     * §3 多码率切换：替换当前 MediaItem 的 URI 但**保持当前播放位置**。
     *
     * 用法：用户在 BitrateSelectorSheet 选档 → VM 拿到新 URL → 调本方法切换；
     * 不重置 position 到 0，避免「切档从头来」。
     */
    fun switchVariant(newAudioUrl: String, newBitrate: Int) {
        if (_currentAudioUrl.value == newAudioUrl) return  // 同一档不切换
        val savedPos = _position.value
        val mediaItem = MediaItem.Builder()
            .setUri(newAudioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(_currentTitle.value)
                    .setArtist(_currentAuthor.value.ifBlank { null })
                    .build(),
            )
            .build()
        player.setMediaItem(mediaItem, savedPos)
        player.prepare()
        player.play()
        _currentAudioUrl.value = newAudioUrl
        _currentBitrate.value = newBitrate
        resetListenCompletionFlag()
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
        resetListenCompletionFlag()
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

        /** 完听判定：进度 ≥ 90% 视为完听 */
        private const val LISTEN_COMPLETE_RATIO = 0.9f
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
