package com.tingxia.audio.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tingxia.audio.audio.di.PlayerModule
import com.tingxia.audio.data.remote.ProgressApi
import com.tingxia.audio.data.remote.ProgressUpdateRequest
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.EntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
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
 *
 * [ERROR] 是 2026-10-23 加的：此前**没有任何** `Player.Listener`，
 * 而播放状态是 [play] 里乐观设成 `PLAYING` 的 —— 于是播放失败（网络断 /
 * 音频 404 / 解码错）时界面毫无反应：进度条不动、按钮还是「暂停」、
 * 什么提示都没有。`ERROR` 让失败成为**可观察的状态**而不是沉默。
 */
enum class PlaybackState { IDLE, PLAYING, PAUSED, STOPPED, ERROR }

/**
 * ExoPlayer 封装 + 真实播放状态（CP4.4）。
 *
 * - 单例（[Singleton]），由 Hilt 通过 [PlayerModule] 注入**共享** ExoPlayer 实例。
 * - 同一 ExoPlayer 也被 [AudioPlayerService]（MediaSessionService）持有并绑定 MediaSession，
 *   因此锁屏 / 通知 / 后台播放统一控制这一份播放器（修复双 ExoPlayer 架构断裂）。
 * - 暴露 [StateFlow] 给 Compose UI：[state] 播放状态、[position] / [duration] 进度，
 *   [currentTitle] / [currentAuthor] / [currentAudioUrl] 当前曲目元数据（供全屏/迷你条展示）。
 * - 命令：[play] / [pause] / [stop] / [seekTo] / [resume]，转发给内部 [ExoPlayer]。
 * - 每 500ms 轮询一次 [ExoPlayer.getCurrentPosition] 更新 [position] / [duration]；
 *   **仅当有 UI 在观察时**才是 500ms，没人看且还在播时退回 10s 慢跳（喂满听
 *   判定与断点续听），没人看又没在播时完全停表 —— 详见 [positionHandler] 上的说明。
 *
 * UI（[com.tingxia.audio.ui.components.AudioPlayerBar]）通过 [PlayerControllerEntryPoint]
 * 在 Compose 内取到本单例并 collect 其 StateFlow。
 *
 * CP11.0.1: 断点续听 — 每 10 秒上报 progress（仅播放中）。
 */
@Singleton
class PlayerController @Inject constructor(
    private val player: ExoPlayer,
    // 「继续播放」的状态要落盘（SharedPreferences），而 SharedPreferences 需要 Context。
    // 注入 ApplicationContext 而不是从 player 里刨 —— ExoPlayer 不对外暴露它。
    @ApplicationContext private val appContext: Context,
) {

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /**
     * 播放失败的**用户可读**原因（2026-10-23 新增）。
     *
     * 为什么单独一个流而不是把文案塞进状态枚举：状态是给控制逻辑用的
     * （按钮显示暂停还是播放），文案是给人看的，两者生命周期不同 ——
     * 状态回到 PLAYING 时错误文案必须**同时**清掉，否则会留着一句
     * 「网络不给力」在已经正常播放的界面上。
     *
     * 文案统一走 [com.tingxia.audio.ui.ErrorMessages.friendlyError] ——
     * 那是仓内唯一的错误文案定义处，不在这里另写一套。
     */
    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

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

    /**
     * 当前曲目的封面 URL —— [retry] 重播时要**连封面一起**还原，
     * 否则重试成功后通知栏/播放条会变成「有标题没封面」。
     */
    private val _currentCoverUrl = MutableStateFlow<String?>(null)

    /**
     * 播放器的状态监听（2026-10-23 新增）。
     *
     * 此前**全项目零 `Player.Listener`**，而播放状态是 [play] 里乐观设成
     * `PLAYING` 的 —— 后果是播放失败（网络断 / 音频 404 / 解码错）时
     * 界面毫无反应：进度条不动、按钮还是「暂停」、没有任何提示。
     * 用户只能看到「按了没声音」，既不知道失败了，也不知道该干什么。
     *
     * 这里只处理**状态与错误**，不碰进度（进度仍由轮询泵负责，
     * 理由见 [positionHandler] 的说明）。
     */
    private val playerListener = object : Player.Listener {

        override fun onPlayerError(error: PlaybackException) {
            // PlaybackException 只是个壳，真正的原因在 cause 里（通常是 IOException）。
            notifyPlaybackError(error.cause ?: error)
        }

        /**
         * 换曲了（上一首/下一首，或系统媒体面板里直接选了另一项）。
         *
         * 「当前是哪一篇」从 `player.currentMediaItem?.mediaId` 读 ——
         * mediaId 承载的就是 articleId（见 [play]）。
         *
         * 为什么不在 App 侧自己维护游标：播放列表归播放器管之后，
         * **系统媒体面板、耳机按键、通知栏按钮**都能换曲，
         * App 侧再维护一份游标就必然和播放器对不上 ——
         * 两套状态迟早漂移，而漂移的表象是「进度回传报到了错的文章上」
         * （完听判定 / 评分卡挂到别的文章）。
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val articleId = mediaItem?.mediaId
            if (!articleId.isNullOrBlank() && articleId != _currentArticleId.value) {
                _currentArticleId.value = articleId
                _currentAudioUrl.value = mediaItem.localConfiguration?.uri?.toString() ?: _currentAudioUrl.value
                _currentTitle.value = mediaItem.mediaMetadata.title?.toString() ?: _currentTitle.value
                _currentAuthor.value = mediaItem.mediaMetadata.artist?.toString() ?: _currentAuthor.value
                resetListenCompletionFlag()
            }
            val idx = player.currentMediaItemIndex
            if (idx != androidx.media3.common.C.INDEX_UNSET) _queueIndex.value = idx
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                if (_state.value != PlaybackState.ERROR) _state.value = PlaybackState.PLAYING
                return
            }
            // 不在播了 —— 要区分「暂停」和「播完了」和「正在缓冲」：
            // 只有 READY（就绪但不播）才是暂停；ENDED 是播完；BUFFERING/IDLE
            // 期间状态**保持不变**（否则缓冲一下就闪成「暂停」）。
            when (player.playbackState) {
                Player.STATE_READY -> if (_state.value != PlaybackState.ERROR) {
                    _state.value = PlaybackState.PAUSED
                }
                Player.STATE_ENDED -> {
                    if (_state.value != PlaybackState.ERROR) _state.value = PlaybackState.IDLE
                    // 完听判定交给轮询泵里的 checkListenCompletion（它会读
                    // STATE_ENDED），这里不重复触发，免得同一篇报两次。
                }
                else -> Unit
            }
        }
    }

    init {
        player.addListener(playerListener)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 睡眠定时器（2026-10-23 新增）
    //
    // 音频 App 的标配：设了 N 分钟就自动停，避免播着播着睡过去。
    //
    // 为什么放在**播放器**这一层而不是界面上：定时器到点的动作是「停止播放」，
    // 而播放是播放器的事；界面只是设置它。放在界面上会出现「页面被销毁 →
    // 定时器跟着没了 → 用户在后台听着，第二天早上还在放」——
    // 而 PlayerController 是进程级单例，定时器活得比界面久。
    // ─────────────────────────────────────────────────────────────────────
    private val _sleepTimerMinutes = MutableStateFlow(0)
    val sleepTimerMinutes: StateFlow<Int> = _sleepTimerMinutes.asStateFlow()

    private val sleepTimerRunnable = Runnable {
        _sleepTimerMinutes.value = 0
        Log.i(TAG, "sleep timer fired")
        pause()
    }

    /**
     * 设置睡眠定时器（分钟）。0 = 取消定时器。
     *
     * 到点做的是 [pause] 而不是 [stop]：暂停保留了播放位置，
     * 用户醒来还能接着听；stop 会把位置清零。
     *
     * 用**主线程 Handler** 而不是协程 delay：Handler 在 Robolectric 里
     * 可以被确定性地推进时间（`ShadowLooper.idleFor`），协程的 delay 做不到 ——
     * 「到点会停」这件事就没法写单测，只能靠真机等一分钟。
     */
    fun setSleepTimerMinutes(minutes: Int) {
        positionHandler.removeCallbacks(sleepTimerRunnable)
        _sleepTimerMinutes.value = minutes.coerceAtLeast(0)
        if (minutes <= 0) return
        positionHandler.postDelayed(sleepTimerRunnable, minutes.toLong() * 60_000L)
    }

    /**
     * 位置轮询泵。这条 Handler 有**两个消费者**，不是只有 UI：     *
     * 1. UI —— Compose 侧 [position] / [duration] 进度条（全屏播放器 + 迷你条）。
     *    它要 500ms 的细粒度才不跳手，但它**只在有人看的时候**才有意义。
     * 2. 非 UI —— [reportProgressIfNeeded]（断点续听，每 10s 一次）与
     *    [checkListenCompletion]（完听判定 / 评分卡）。它们读的是**同一个**
     *    [_position] / [_duration] 缓存，不碰 Handler。
     *
     * 此前只有第 1 类需求，却无条件每 500ms 唤醒一次主线程：
     * [PlayerController] 是 @Singleton，生命周期跟进程走，用户把 App 切到后台
     * 后它照跑不误 —— 8 小时 = 57,600 次主线程唤醒，全部花在没人看的屏幕上。
     *
     * 所以这里改成**按需定频**（见 [nextPositionPollDelayMs]）而不是无条件轮询。
     *
     * 注意这里**不能**简单地在"没人观察"时把泵整个停掉：
     * [reportProgressIfNeeded] 是从 `progressJob`（Dispatchers.IO）里读
     * [_position] 的缓存值，ExoPlayer 又必须在主线程访问、不能跨线程现读。
     * 一旦彻底停泵，后台播放时上报的 position 会**冻在上一次的数**上，
     * 而断点续听不报错 —— 表现为"地铁里听完回来，进度条又跳回去了"，
     * 属于本项目最吃过亏的静默失真。所以保留一个与上报同频的慢跳（10s），
     * 10s 内消费的数据最多陈旧一跳，与原来上报时刻的精度等价。
     */
    private val positionHandler = Handler(Looper.getMainLooper())

    /**
     * 泵是否开着。
     *
     * 不用 `Handler.hasCallbacks` 判断 —— 那是 API 29+，本项目 minSdk 26。
     * 主线程读写（Compose 与 [play]/[pause] 都在主线程），加 @Volatile 只是
     * 防止将来有人从别的线程调进来时读到半新半旧的值。
     */
    @Volatile
    private var positionPumpRunning = false

    /**
     * 正在观察位置的 UI 数量（[startPositionUpdates] / [stopPositionUpdates] 配对）。
     *
     * 全屏播放器和迷你条**同时**可见时会各占一个，所以是计数不是布尔 ——
     * 用布尔会在"关掉其中一个"时把另一个的更新也一起停掉。
     */
    private var positionObserverCount = 0

    private val positionRunnable = object : Runnable {
        override fun run() {
            player.let { p ->
                _position.value = p.currentPosition.coerceAtLeast(0L)
                _duration.value = if (p.duration == C.TIME_UNSET) 0L else p.duration
            }
            // CP3.7.0: 完听判定 —— ExoPlayer STATE_ENDED（已到末尾）
            // 或进度 ≥ 90% 视为完听（手动拖到末尾也算）
            checkListenCompletion()
            scheduleNextPositionTick()
        }
    }

    /**
     * 下一跳的间隔；`null` = **彻底不排**（连慢跳都不排）。
     *
     * 分档依据是"这一跳有没有存在的理由"，而不是有没有在播放：
     * - 有 UI 在观察 → [POLL_INTERVAL_MS]，进度条要跟手。
     * - 没人看但还在播 → [BACKGROUND_POLL_INTERVAL_MS]，够喂满听判定和
     *   断点续听（两者的时间粒度都是 10s，见 [PROGRESS_REPORT_INTERVAL_MS]）。
     * - 没人看且没在播 → null。位置根本不会变，此时任何频率都是纯浪费 ——
     *   此前 [pause] 不动轮询，暂停后放着 App 也能跑到天亮。
     */
    private fun nextPositionPollDelayMs(): Long? = when {
        positionObserverCount > 0 -> POLL_INTERVAL_MS
        _state.value == PlaybackState.PLAYING -> BACKGROUND_POLL_INTERVAL_MS
        else -> null
    }

    /** 取消当前待执行的一跳并按 [nextPositionPollDelayMs] 重排；泵没开则什么都不做。 */
    private fun scheduleNextPositionTick() {
        positionHandler.removeCallbacks(positionRunnable)
        if (!positionPumpRunning) return
        nextPositionPollDelayMs()?.let { positionHandler.postDelayed(positionRunnable, it) }
    }

    /**
     * 注册一个"我要看进度条"的观察者（由 UI 经生命周期感知调用）。
     *
     * 注册后立刻重排：从后台慢跳切回 500ms 不用先等完当前那一跳，
     * 否则用户点开全屏播放器会有最长 [BACKGROUND_POLL_INTERVAL_MS] 的进度条卡住。
     */
    fun startPositionUpdates() {
        positionObserverCount++
        if (positionObserverCount > 0) scheduleNextPositionTick()
    }

    /**
     * 注销观察者。最后一个注销时退回慢跳 —— 声音继续放，
     * 只是不再有人需要 500ms 的刷新率了。
     *
     * 计数已为 0 时调用是 no-op：这样 UI 侧 `onDispose` 可以无条件配对调用，
     * 不必先判断"当初到底有没有 start 过"。
     */
    fun stopPositionUpdates() {
        if (positionObserverCount > 0) positionObserverCount--
        if (positionObserverCount == 0) scheduleNextPositionTick()
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
                // 2026-10-02: 之前一直发 total_sec = null。
                // 服务端 listening_statuses.total_sec 因此永远是 NULL —— 而
                // 完听率正是靠 position_sec / total_sec 算的，缺了分母，
                // 「跳过率 / 完听率 / 平均单次收听时长」这三个画像特征
                // 就永远算不出来，个性化无从学习。
                api.updateProgress(
                    articleId,
                    ProgressUpdateRequest(
                        position_sec = positionSec,
                        total_sec = if (durationMs > 0) (durationMs / 1000).toInt() else null,
                    )
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
        _playbackError.value = null
        _currentTitle.value = title
        _currentAuthor.value = author ?: ""
        _currentAudioUrl.value = audioUrl
        _currentCoverUrl.value = coverUrl
        // mediaId 承载 articleId —— 「当前播的是哪一篇」从播放器直接读得出。
        //
        // ⚠️ 没有 articleId 时**不设** mediaId（而不是塞 URL）：
        // 监听器会用 mediaId 覆盖 _currentArticleId，塞 URL 就等于
        // 把「当前文章」变成一个 URL —— 完听判定与进度回传会报到一个
        // 不存在的文章上。而 `play()` 不传 articleId 的那条路径（全屏播放器的
        // IDLE 恢复）语义是「**沿用**原来的文章」，所以这里要沿用
        // _currentArticleId 而不是置空。
        val mediaId = articleId ?: _currentArticleId.value
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(audioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(author)
                    .setArtworkUri(coverUrl?.let(Uri::parse))
                    .build(),
            )
        if (!mediaId.isNullOrBlank()) mediaItemBuilder.setMediaId(mediaId)
        val mediaItem = mediaItemBuilder.build()

        // 2026-10-23：**把队列灌进播放器**。
        //
        // 为什么非灌不可：Media3 1.4.1 的通知按钮依据是
        // `player.getAvailableCommands()` —— 单曲播放器的
        // COMMAND_SEEK_TO_NEXT 不可用 → **通知栏/锁屏/系统媒体面板
        // 永远没有「下一首」按钮**。App 里自己画按钮绕不过这一层。
        //
        // 灌的时机是「开始播放这一刻」，而不是列表每次刷新 ——
        // setMediaItems 会重新 prepare 当前项，列表刷新时正在播就会被
        // 打断（声音卡一下）。详见 [setQueue] 的说明。
        val queue = _queue.value
        val queueIndex = articleId?.let { id -> queue.indexOfFirst { it.articleId == id } } ?: -1
        if (queueIndex >= 0) {
            player.setMediaItems(queue.map { it.toMediaItem() }, queueIndex, 0L)
            _queueIndex.value = queueIndex
        } else {
            // 不在队列里（比如从推送/分享直接进详情）→ 单曲播放。
            player.setMediaItem(mediaItem)
        }
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
        // 记下「刚才在听什么」—— 系统媒体面板的「继续播放」要用（见 PlaybackResumption）。
        PlaybackResumption.save(appContext, articleId, audioUrl, title)
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
     *
     * ## 这里**不**动播放器的播放列表（2026-10-23）
     *
     * 播放列表在**播放开始的那一刻**才灌进播放器（见 [play]）。
     * 原因是：`setMediaItems` / `replaceMediaItems` 会重新 prepare 当前项 ——
     * 列表每次刷新都调本方法，而列表刷新可能发生在**正在播放**时
     * （用户从别的页面回列表 → 列表重新加载 → 正在播的那一篇被重新 prepare
     * → 声音卡一下，位置还可能丢）。
     *
     * 所以这里只更新 App 侧的队列镜像；播放器的播放列表是
     * 「开始播放那一刻的快照」，由 [play] 灌一次。
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

    /**
     * 跳到下一首；已在末尾则 no-op 返回 false。
     *
     * 2026-10-23 起**由播放器接管**：播放列表已经在 [play] 里灌进了 ExoPlayer，
     * 所以这里用 `seekToNextMediaItem()`，而不是自己维护游标 + 重建 MediaItem。
     *
     * 这么改的意义：通知栏/锁屏/系统媒体面板的「下一首」走的是**同一个**
     * 播放列表（Media3 的按钮看的是 `player.getAvailableCommands()`，
     * 单曲播放器就没有这个命令 → 通知栏永远没有「下一首」按钮）。
     * App 内自己维护游标、系统用播放器的列表 = 两套队列 = 两套真相，
     * 用户在通知栏点「下一首」和 App 里点「下一首」会跳到不同的曲子。
     */
    fun playNext(): Boolean {
        if (!player.hasNextMediaItem()) return false
        player.seekToNextMediaItem()
        return true
    }

    /** 跳到上一首；已在开头则 no-op 返回 false。 */
    fun playPrevious(): Boolean {
        if (!player.hasPreviousMediaItem()) return false
        player.seekToPreviousMediaItem()
        return true
    }

    /**
     * 把队列条目转成播放器的 MediaItem。
     *
     * `mediaId` 承载 **articleId** —— 这样「当前播的是哪一篇」可以直接从
     * 播放器读出来（`player.currentMediaItem?.mediaId`），不必再单独维护一个
     * 字段跟播放列表对账（两个状态迟早会不一致）。
     */
    private fun QueueItem.toMediaItem(): MediaItem = MediaItem.Builder()
        .setUri(audioUrl)
        .setMediaId(articleId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title ?: "")
                .setArtist(author)
                .build(),
        )
        .build()

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
        // 2026-10-23：播放列表模式下不能 `setMediaItem` ——
        // 那会把整个播放列表换成**一项**（通知栏/系统面板的「下一首」当场消失）。
        // 正确做法是替换**当前那一项**（replaceMediaItem），播放列表原封不动。
        //
        // ⚠️ replaceMediaItem **不保留播放位置**（Media3 文档明写），
        // 所以要自己 seekTo 回去 —— 否则「切档从头来」，
        // 而这正是这个方法当初要解决的问题。
        val index = player.currentMediaItemIndex
        if (index != C.INDEX_UNSET && player.mediaItemCount > 0) {
            player.replaceMediaItem(index, mediaItem)
            player.seekTo(index, savedPos)
        } else {
            player.setMediaItem(mediaItem, savedPos)
        }
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

    /**
     * 播放失败的处理入口（2026-10-23 新增，internal 便于单测）。
     *
     * 抽成方法而不是只写在 [Player.Listener] 里，是为了**可测**：
     * 触发真实的 ExoPlayer 错误要靠网络/解码失败，不确定而且慢；
     * 而这里要断言的是「状态变 ERROR + 文案友好 + 不再上报进度」——
     * 那是本方法的语义，与错误**怎么来的**无关。
     *
     * 文案统一走 [friendlyError] —— 仓内唯一的错误文案定义处。
     */
    internal fun notifyPlaybackError(cause: Throwable) {
        val message = friendlyError(cause, fallback = "播放失败，请点重试")
        Log.w(TAG, "playback error: article=${_currentArticleId.value}, cause=$cause")
        _playbackError.value = message
        _state.value = PlaybackState.ERROR
        // 失败后不要再上报进度：位置停住了，继续上报只会把「卡住的位置」
        // 当成「听到的位置」写进服务端，断点续听会跳回一个错误的位置。
        stopProgressReporting()
    }

    /**
     * 重播当前曲目（2026-10-23 新增）。
     *
     * 存在的理由：播放失败后状态是 [PlaybackState.ERROR]，而 [resume] 只认
     * PAUSED —— 于是用户在「播放失败」的界面上按播放键**什么都不会发生**
     * （按钮点了没反应，是最让人抓狂的一类死按钮）。
     *
     * 这里用 [play] 重来一遍而不是 `player.prepare()`：失败可能来自
     * 音频地址失效（服务器换了 OSS 签名），重播时应当**重新取一次地址** ——
     * 而 [play] 的入参来自调用方，重试正好能把最新的地址带进来。
     * 当前这一路（自己持有的 URL）则原样重来。
     */
    fun retry() {
        val url = _currentAudioUrl.value
        if (url.isBlank()) {
            // 没有可重播的地址（比如还没拿到 audioUrl 就失败了）→ 回到 IDLE，
            // 让 UI 显示「未在播放」而不是一直挂着错误。
            _state.value = PlaybackState.IDLE
            _playbackError.value = null
            return
        }
        play(
            audioUrl = url,
            title = _currentTitle.value,
            author = _currentAuthor.value.ifBlank { null },
            coverUrl = _currentCoverUrl.value,
            articleId = _currentArticleId.value,
        )
    }

    fun pause() {
        _state.value = PlaybackState.PAUSED
        player.pause()
        // 位置也记一份：恢复时要从这儿接着听。
        PlaybackResumption.savePosition(appContext, _position.value)
        // 暂停后 position 不再变化，此刻没有任何一方的需求要 10s 粒度的数据，
        // 于是把慢跳也撤掉。UI 仍在观察时 scheduleNextPositionTick 会保留 500ms，
        // 让用户拖完进度条还能看到位置。
        scheduleNextPositionTick()
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
     * 快退 / 快进（2026-10-23 新增）。
     *
     * 步长取 [com.tingxia.audio.audio.di.PlayerModule] 里的唯一定义 ——
     * 那里同时也是 ExoPlayer 的 `setSeekBackIncrementMs` /
     * `setSeekForwardIncrementMs`，所以 App 内 UI、通知栏按钮、耳机双击、
     * 系统媒体面板用的是**同一个步长**（10s / 30s）。
     *
     * 为什么不各写各的：之前 App 内 UI 退 10 秒，而 ExoPlayer 默认是 5 秒 ——
     * 同一个「快退」在不同入口有两个步长，用户按耳机键和点屏幕得到的结果
     * 不一样，是最容易被当成「App 抽风」的那类不一致。
     */
    fun seekBack() {
        val target = (_position.value - PlayerModule.SEEK_BACK_MS).coerceAtLeast(0L)
        seekTo(target)
    }

    fun seekForward() {
        val dur = _duration.value
        val target = if (dur > 0L) {
            (_position.value + PlayerModule.SEEK_FORWARD_MS).coerceAtMost(dur)
        } else {
            _position.value + PlayerModule.SEEK_FORWARD_MS
        }
        seekTo(target)
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
        positionPumpRunning = true
        scheduleNextPositionTick()
    }

    private fun stopPolling() {
        positionPumpRunning = false
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

        /**
         * 后台慢跳间隔 —— **不是**拍脑袋的数，取的是
         * [PROGRESS_REPORT_INTERVAL_MS]：断点续听每 10s 读一次缓存的 position，
         * 所以 10s 采一次刚好让消费方拿到最新一拍。再密只是白唤醒主线程，
         * 再疏则上报值可能落后一到两跳。
         */
        private const val BACKGROUND_POLL_INTERVAL_MS = 10_000L
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
