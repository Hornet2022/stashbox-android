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

    // CP-TTS-VOICE: 播放语速。
    //
    // 此前 App 有一个**假闭环**：FullScreenPlayerScreen 里有 0.75x~2.0x 五档 UI，
    // 但 currentSpeed 是 `remember { mutableStateOf("1.0x") }` 纯本地状态，
    // 而本类从来没有 setSpeed —— 点了播放器速度纹丝不动，切页面/重启即丢。
    // 现在真正落到 ExoPlayer.setPlaybackSpeed。
    //
    // 为什么变速放播放端而不是合成端：改语速不重跑蒸馏。实测单篇蒸馏约 23 分钟，
    // 让用户为了调速等一刻钟是不可接受的。代价是变速后音调会跟着变
    // （变调不变速需要额外的 pitch correction，本项目没有）。
    private val _speed = MutableStateFlow(DEFAULT_SPEED)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** 可选档位由服务端下发（common/models/tts_voice.py: DEFAULT_PLAYBACK_SPEEDS）。 */
    private val _availableSpeeds = MutableStateFlow(DEFAULT_AVAILABLE_SPEEDS)
    val availableSpeeds: StateFlow<List<Float>> = _availableSpeeds.asStateFlow()

    /**
     * 设置播放语速。
     *
     * @param value 倍速，落在 MIN~MAX 之外时**夹紧**而不是抛异常 ——
     *   语速是个滑块，用户拖过头不该让播放器崩掉。
     */
    fun setSpeed(value: Float) {
        val clamped = value.coerceIn(MIN_SPEED, MAX_SPEED)
        _speed.value = clamped
        player.setPlaybackSpeed(clamped)
    }

    /**
     * 拉取服务端下发的语速档位与用户偏好并应用。
     *
     * 放在这里而不是单独 Repository：语速是**播放器参数**，且必须在
     * ExoPlayer 上生效才叫「配了」。只在启动/登录后调一次即可
     * （App 生命周期内 PlayerController 是单例）。
     */
    fun applySpeedPreference(preferred: Float, options: List<Float>) {
        if (options.isNotEmpty()) _availableSpeeds.value = options
        setSpeed(preferred)
    }

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

    /**
     * 本地进度仓库（2026-10-02）。
     *
     * 之前进度只写网络，异常 `Log.w` 就丢了；断网期间播的进度一个字节都没留下。
     * 现在先落 Room（本地），再异步同步服务端 —— 网络失败只是"晚点补传"，
     * 不是"丢失"。
     */
    @Volatile
    private var progressRepository: com.tingxia.audio.data.repository.ProgressRepository? = null

    /** 设置 ProgressApi（由 Hilt 注入，ArticleDetailViewModel 调用）。 */
    fun setProgressApi(api: ProgressApi) {
        progressApi = api
    }

    /**
     * 设置本地进度仓库。
     *
     * 走 Hilt EntryPoint 取而不是构造注入：PlayerController 是 @Singleton 且
     * 在 [com.tingxia.audio.audio.AudioPlayerService] 里也会建一份，构造注入
     * 会形成环。
     */
    fun setProgressRepository(repo: com.tingxia.audio.data.repository.ProgressRepository) {
        progressRepository = repo
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
        val durationMs = _duration.value

        progressScope.launch {
            // ① 先落本地：这一步不该依赖网络。地铁里断网时它是唯一的真相来源。
            progressRepository?.let { repo ->
                runCatching {
                    repo.saveLocalProgress(articleId, positionMs, durationMs)
                }.onFailure { Log.w(TAG, "本地进度落盘失败: ${it.message}") }
            }

            // ② 再同步服务端：失败不丢数据，留在 Room 里等 ProgressSyncWorker 补传。
            try {
                api.updateProgress(
                    articleId,
                    ProgressUpdateRequest(position_sec = positionSec, total_sec = null)
                )
                progressRepository?.let { runCatching { it.markSyncedLocally(articleId) } }
            } catch (e: Exception) {
                Log.w("ProgressApi", "服务端上报失败（已存本地，稍后补传）: ${e.message}")
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
        // CP-TTS-VOICE: setPlaybackSpeed 是 player 级参数，理论上前后换 MediaItem
        // 不会丢。但这里显式重放一次 —— 漏掉的代价是「用户设了 1.5x，点开下一篇
        // 悄悄变回 1.0x」，而这种静默失灵本项目已经吃过很多次（见
        // backend/tests/e2e/README.md 的踩坑清单）。重放一次是幂等且零成本的。
        player.setPlaybackSpeed(_speed.value)
        player.play()
        resetListenCompletionFlag()
        startPolling()
        startProgressReporting()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 播放队列（2026-10-02 新增）
    //
    // 之前 FullScreenPlayerScreen 的「上一首 / 下一首」是
    // `onSkipPrevious = { /* 占位 */ }` —— 点下去毫无反应。
    // 根因不是按钮没接线，而是**整条链路没有队列**（本类原先 queue 命中数为 0）：
    // 播放器只认「当前这一篇」，不知道前后是什么。
    //
    // 队列由列表页灌入（把当前可见的 ready 文章按顺序排好），播放器只负责
    // 维护游标和切换 —— 职责分明，列表刷新不影响正在播的那一篇。
    // ─────────────────────────────────────────────────────────────────────

    /** 队列条目。最小集：能播就行，其余 UI 需要的字段让界面自己带。 */
    data class QueueItem(
        val articleId: String,
        val audioUrl: String,
        val title: String? = null,
        val author: String? = null,
    )

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    private val _queueIndex = MutableStateFlow(-1)

    val hasNext: Boolean get() = _queueIndex.value in 0 until _queue.value.size - 1
    val hasPrevious: Boolean get() = _queueIndex.value > 0

    /**
     * 灌入队列。列表页在数据加载完时调。
     *
     * 已经在播的那一篇会被定位到队列中的对应位置，这样「下一首」才是
     * 用户视线里那篇，而不是从头开始。
     */
    fun setQueue(items: List<QueueItem>) {
        if (items.isEmpty()) {
            _queue.value = emptyList()
            _queueIndex.value = -1
            return
        }
        _queue.value = items
        val currentId = _currentArticleId.value
        _queueIndex.value = items.indexOfFirst { it.articleId == currentId }
            .takeIf { it >= 0 } ?: 0
    }

    /** 跳到下一首；已在末尾则 no-op 返回 false。 */
    fun playNext(): Boolean {
        val items = _queue.value
        val next = _queueIndex.value + 1
        if (next !in items.indices) return false
        _queueIndex.value = next
        playQueueItem(items[next])
        return true
    }

    /** 跳到上一首；已在开头则 no-op 返回 false。 */
    fun playPrevious(): Boolean {
        val items = _queue.value
        val prev = _queueIndex.value - 1
        if (prev !in items.indices) return false
        _queueIndex.value = prev
        playQueueItem(items[prev])
        return true
    }

    private fun playQueueItem(item: QueueItem) {
        play(item.audioUrl, item.title ?: "", articleId = item.articleId, author = item.author)
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

        // CP-TTS-VOICE: 语速边界与默认档位。
        // 与服务端 common/models/tts_voice.py 的 MIN/MAX_PLAYBACK_SPEED 保持一致 ——
        // 服务端会校验越界值并返 400，这里只是客户端侧的兜底夹紧。
        const val DEFAULT_SPEED = 1.0f
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f

        /** 服务端不可达时的兜底档位；正常以服务端下发的 available_speeds 为准 */
        val DEFAULT_AVAILABLE_SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
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
