package com.tingxia.audio.ui.articles

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.model.MyEvaluationResponse
import com.tingxia.audio.data.remote.ProgressApi
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.data.repository.EvaluationRepository
import com.tingxia.audio.data.repository.ProgressRepository
import com.tingxia.audio.ui.components.sourceLabelRes
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 详情页 ViewModel。
 *
 * 数据流：
 * 1. [loadArticle] → GET /api/v1/articles/{id} 拿 article（含 taskId + status）
 * 2. 若 status != ready → [startPolling] 每 [POLL_INTERVAL_MS] 秒
 *    GET /api/v1/articles/{id}/status（§1.3 新路径），直到 status == ready / failed
 * 3. ready → 拉 audio_url 并显示播放器；failed/超时 → 标记错误
 * 4. **CP3.7.0 评分闭环**：
 *    - 监听 [PlayerController.listenCompleted]，触发 §2.2 listen-complete 静默上报
 *    - 同时置 [shouldShowEvaluationDialog]，UI 侧收集后弹 §2.6 4 维评分卡
 *
 * CP11.0.1: 断点续听 — 进入时 GET progress，有记录则从断点起播。
 */
@HiltViewModel
class ArticleDetailViewModel @Inject constructor(
    private val repository: ArticleRepository,
    private val progressRepository: ProgressRepository,
    private val progressApi: ProgressApi,
    private val playerController: PlayerController,
    private val evaluationRepository: EvaluationRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /**
     * 播放器的"作者"字段用来源中文标签，而不是原始 source 码。
     *
     * 这个值最终进 MediaSession，会显示在锁屏卡片和通知栏上；
     * 直接塞 article.source 的后果是真机通知栏写着 "unknown" / "d9"。
     */
    private fun authorLabel(source: String?): String =
        context.getString(sourceLabelRes(source))

    private val _uiState = MutableStateFlow(ArticleUiState())
    val uiState: StateFlow<ArticleUiState> = _uiState.asStateFlow()

    // CP5.2-A: retry 状态
    private val _retryState = MutableStateFlow<RetryState>(RetryState.Idle)
    val retryState: StateFlow<RetryState> = _retryState.asStateFlow()

    // CP-DELETE: 删除状态（UI 观察 Success 后回列表）
    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()

    // CP3.7.0: 蒸馏任务 id（§2.6 evaluation 入参）
    val taskId: StateFlow<String?> get() = _taskId
    private val _taskId = MutableStateFlow<String?>(null)

    // CP3.7.0: 是否弹评分卡（完听后 → true）
    private val _shouldShowEvaluationDialog = MutableStateFlow(false)
    val shouldShowEvaluationDialog: StateFlow<Boolean> = _shouldShowEvaluationDialog.asStateFlow()

    /**
     * 我对当前这篇的最新听感评分（读回）。
     *
     * null = 未知（还没拉 / 拉失败 / 没有 taskId）。
     * 用"未知"而不是"没评过"是有意的：拉取失败不该让用户以为没评过。
     */
    private val _myRating = MutableStateFlow<MyEvaluationResponse?>(null)
    val myRating: StateFlow<MyEvaluationResponse?> = _myRating.asStateFlow()

    /** 已确认评过分（读回成功且有记录）。 */
    val isRated: Boolean get() = _myRating.value?.isRated == true

    /**
     * 评分提交成功后由 UI 回调，让详情页立刻显示"已评分 ★N"。
     *
     * 不用重新读回：提交响应里已经有 id / task_id / overall_score，够画这一行，
     * 省一次请求也避免刚提交完还短暂显示成"未评分"。
     */
    fun markRated(overallScore: Int, evaluationId: String, taskId: String) {
        _myRating.value = MyEvaluationResponse(
            id = evaluationId,
            taskId = taskId,
            overallScore = overallScore,
        )
    }

    // CP-DELETE: 轮询协程句柄 —— 删除时取消，避免对已删文章继续 GET distill/{task_id}
    private var pollingJob: kotlinx.coroutines.Job? = null

    // CP3.7.0: 听感上报守卫（一篇只报一次）
    private var listenCompletionReported = false

    private var savedPositionMs: Long? = null

    init {
        // CP3.7.0: 监听完听事件 → 静默上报 §2.2 + 弹评分卡
        viewModelScope.launch {
            playerController.listenCompleted.collect { ts ->
                if (ts != null && !listenCompletionReported) {
                    // 只认**文章 id**。
                    //
                    // 原写法是 `_taskId.value ?: article?.id`，而 `_taskId` 是蒸馏任务
                    // id（`distilled_articles.id`），§2.2 的端点要的却是
                    // `articles/{article_id}/listen-complete`。READY 的文章几乎都有
                    // taskId，所以这个 ?: 基本永远走第一分支 → 拿任务 id 当文章 id 提交
                    // → 后端 `_get_owned` 查不到 → 404，上报静默失败。
                    // 真机证据：feedback 表 type=listen_complete 至今只有 2 条，
                    // 远低于 audio_play_start 的 71 条。
                    val articleId = _uiState.value.article?.id ?: return@collect
                    listenCompletionReported = true
                    reportListenComplete(articleId)
                    _shouldShowEvaluationDialog.value = true
                }
            }
        }
    }

    fun loadArticle(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // 断点续听：本地优先（断网可用），本地没有才回源。
                // 2026-10-02 改：之前是 `runCatching { 远端 }`，网络一失败就
                // savedPositionMs 保持 null，后面 seekTo 整段跳过 → 地铁里
                // 断网重开文章必然从头播，用户听到的是"我明明听到 20 分钟了"。
                savedPositionMs = progressRepository.getResumePositionMs(id)

                val article = repository.getArticle(id)
                _taskId.value = article.taskId
                // 评分闭环读侧：进页面就拉"我评过没"，UI 才能显示"已评分 ★N"
                article.taskId?.let { taskId ->
                    viewModelScope.launch {
                        runCatching { evaluationRepository.getMyRating(taskId) }
                            .onSuccess { _myRating.value = it }
                            .onFailure {
                                // 拉不到不阻断：当作"未知"，仍允许评分
                                _myRating.value = null
                                android.util.Log.w(TAG, "load my rating failed: ${it.message}")
                            }
                    }
                }
                // 文章已就绪（或已听）时，首屏直接取 article.audioUrl，无需轮询
                val initialAudioUrl =
                    if (article.status == DistillStatus.READY ||
                        article.status == DistillStatus.LISTENED
                    ) {
                        article.audioUrl
                    } else {
                        null
                    }
                _uiState.update {
                    it.copy(
                        article = article,
                        status = article.status,
                        audioUrl = initialAudioUrl,
                        isLoading = false,
                    )
                }
                // CP4.4：蒸馏已就绪，直接起播
                initialAudioUrl?.let { url ->
                    playerController.setProgressApi(progressApi)
                    playerController.setProgressRepository(progressRepository)
                    playerController.play(
                        url,
                        title = article.title ?: "",
                        author = authorLabel(article.source),
                        articleId = id,
                    )
                    savedPositionMs?.let { pos ->
                        playerController.seekTo(pos)
                    }
                }
                // 文章没 ready 且有 taskId → 启动轮询
                if (article.taskId != null &&
                    article.status != DistillStatus.READY &&
                    article.status != DistillStatus.LISTENED
                ) {
                    startPolling(id, article.taskId)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = friendlyError(e, fallback = "加载失败")) }
            }
        }
    }

    /**
     * §1.3 新路径：按 articleId 轮询（取代旧 taskId-only 路径）。
     *
     * ready 后 §1.3 响应已自带 audio_url，可省去再调一次 §1.4 的往返（仅在
     * §1.3 响应缺 audio_url 时回退 §1.4）。
     */
    private fun startPolling(articleId: String, taskId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            var attempts = 0
            // ⚠️ 2026-10-03 修：原来连续网络失败直接 `return@launch` 终止整个轮询，
            // 且没有重试入口。一次地铁/电梯的基站切换、一次 5xx、一次 65s 超时就让
            // 「剪藏 → 等 12~23 分钟 → 收听」这条主链路永久停在「处理中」——
            // 服务端其实正常跑完了，但页面没有错误、没有重试、音频栏不出现。
            //
            // 改成：单次失败只记一次并继续下一轮；连续失败超过阈值才放弃，
            // 且放弃时给出可重试的提示（而不是「静默停止」）。
            var consecutiveFailures = 0
            while (attempts < MAX_POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                attempts++
                try {
                    val resp = repository.getArticleStatus(articleId)
                    // 成功一次就重置连续失败计数 —— 否则「好一次、坏一次」的抖动
                    // 会在 3 个周期内累计到阈值，把其实连得上的服务误判成不可达
                    consecutiveFailures = 0
                    when (resp.status) {
                        DistillStatus.READY -> {
                            // §1.3 响应若带 audio_url 直接用；否则回退 §1.4
                            val url = resp.audioUrl
                                ?: runCatching { repository.getAudioUrl(articleId).audio_url }.getOrNull()
                            _uiState.update {
                                it.copy(
                                    status = DistillStatus.READY,
                                    audioUrl = url,
                                    tags = resp.tags.orEmpty(),
                                    qualityScore = resp.qualityScore,
                                    audioDurationSec = resp.audioDurationSec,
                                )
                            }
                            val art = _uiState.value.article
                            url?.let {
                                playerController.setProgressApi(progressApi)
                    playerController.setProgressRepository(progressRepository)
                                playerController.play(
                                    it,
                                    title = art?.title ?: "",
                                    author = authorLabel(art?.source),
                                    articleId = articleId,
                                )
                                savedPositionMs?.let { pos ->
                                    playerController.seekTo(pos)
                                }
                            }
                            return@launch
                        }
                        DistillStatus.FAILED -> {
                            _uiState.update { it.copy(status = DistillStatus.FAILED, pollError = "蒸馏失败") }
                            return@launch
                        }
                        else -> _uiState.update { it.copy(status = resp.status) }
                    }
                } catch (e: Exception) {
                    // 单次网络失败不终止轮询：记一次、继续下一轮
                    consecutiveFailures++
                    _uiState.update { it.copy(pollError = e.message ?: "轮询失败，正在重试") }
                    if (consecutiveFailures >= MAX_CONSECUTIVE_POLL_FAILURES) {
                        _uiState.update {
                            it.copy(
                                pollError = "连续 ${MAX_CONSECUTIVE_POLL_FAILURES} 次无法连接服务" +
                                    "（${e.message ?: "网络错误"}）。蒸馏可能仍在服务端进行，可下拉重试。",
                            )
                        }
                        return@launch
                    }
                }
            }
            // 超过最大尝试次数仍未 ready → 超时
            _uiState.update { it.copy(pollTimedOut = true, pollError = "蒸馏超时（>90s）") }
        }
    }

    /** §2.2 静默上报 listen-complete。失败不弹错误（后台埋点性质）。 */
    private fun reportListenComplete(articleId: String) {
        viewModelScope.launch {
            runCatching {
                val durationSec = (playerController.duration.value / 1000L).toInt()
                    .takeIf { it > 0 }
                repository.markListened(articleId, durationSec)
            }
        }
    }

    /**
     * §1.4 URL 过期前刷新：audioUrl 在 [expiresAt] 60s 内即视为过期，重新拉取。
     * 用于恢复播放 / 切档前调用，避免播到一半拿到 403/Expired URL。
     */
    fun refreshAudioUrlIfExpiringSoon(articleId: String) {
        val state = _uiState.value
        val currentUrl = state.audioUrl ?: return
        val existing = audioUrlCache.expiryByArticle[articleId] ?: return
        if (!existing.isExpiringSoon()) return
        viewModelScope.launch {
            runCatching { repository.getAudioUrl(articleId) }
                .onSuccess { resp ->
                    audioUrlCache.expiryByArticle[articleId] = ExpiryInfo(
                        url = resp.audio_url,
                        expiresAt = resp.expiresAt?.let { runCatching { parseIsoTime(it) }.getOrNull() },
                    )
                    val newUrl = resp.audio_url
                    if (newUrl != currentUrl) {
                        _uiState.update { it.copy(audioUrl = newUrl) }
                        playerController.switchVariant(newUrl, playerController.currentBitrate.value)
                    }
                }
        }
    }

    private fun parseIsoTime(s: String): Long {
        // 简易解析：ZonedDateTime 兼容带时区 ISO8601；不带时区的退化为 LocalDateTime
        return runCatching {
            java.time.ZonedDateTime.parse(s).toInstant().toEpochMilli()
        }.getOrElse {
            runCatching {
                java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            }.getOrDefault(0L)
        }
    }

    /** URL + 过期时间的内存缓存（跨详情页进入复用） */
    private val audioUrlCache = object {
        val expiryByArticle: MutableMap<String, ExpiryInfo> = mutableMapOf()
    }

    private data class ExpiryInfo(val url: String, val expiresAt: Long?) {
        fun isExpiringSoon(): Boolean {
            val e = expiresAt ?: return false  // 无过期信息 → 不主动刷
            return System.currentTimeMillis() >= e - URL_REFRESH_LEAD_MS
        }
    }

    companion object {
        private const val TAG = "ArticleDetailViewModel"
        /**
         * 轮询间隔：15 秒（2026-10-03 从 3s 拉长）。
         *
         * 原来 3s × 30 次 = **90 秒窗口**，但实测单篇蒸馏要 12 分钟
         * （本机 TTS 约 745s/篇，生产最长音频 598s）—— 也就是说轮询**必然在
         * 蒸馏完成之前就放弃**，用户看到「蒸馏超时」，而服务端还在跑。
         * 这比「网络抖动导致提前放弃」更根本。
         *
         * 15s 对分钟级的操作完全够用：等待窗口本来就是分钟级，3s 的刷新密度
         * 对用户毫无感知价值，却把请求量放大了 5 倍（多篇并发时是 N 倍）。
         */
        const val POLL_INTERVAL_MS: Long = 15_000L

        /**
         * 最大轮询次数：60 次 × 15s ≈ 15 分钟。
         *
         * 覆盖 12 分钟的实测耗时并留出余量；到点仍未 ready 判为超时，
         * 提示用户稍后回来查看（蒸馏任务在服务端是独立跑的，不会被掐断）。
         */
        const val MAX_POLL_ATTEMPTS: Int = 60

        /**
         * 连续网络失败多少次才放弃轮询（2026-10-03）。
         *
         * 3 次 × 3s 间隔 ≈ 9 秒，足够熬过一次基站切换/瞬时 5xx，又不会让
         * 真断网的用户白等太久。成功一次就重置（见 startPolling）。
         */
        const val MAX_CONSECUTIVE_POLL_FAILURES: Int = 3

        /** URL 过期前 60 秒主动刷新 */
        const val URL_REFRESH_LEAD_MS: Long = 60_000L
    }

    /** §2.3 用户在播放器主动跳过 */
    fun reportSkip(reason: String = "other") {
        val articleId = _uiState.value.article?.id ?: return
        viewModelScope.launch {
            runCatching { repository.skipArticle(articleId, reason) }
        }
    }

    /** §2.1 旧版 1-5 星评分（保留双轨之一，新 UI 主推 §2.6 4 维评分） */
    fun rateArticle(rating: Int, comment: String? = null) {
        val articleId = _uiState.value.article?.id ?: return
        viewModelScope.launch {
            runCatching { repository.rateArticle(articleId, rating, comment) }
                .onSuccess { android.util.Log.i(TAG, "rateArticle ok: $rating") }
                .onFailure { android.util.Log.w(TAG, "rateArticle failed: ${it.message}") }
        }
    }

    /** UI 关闭评分弹窗后调用，避免下次进入详情页还显示 */
    fun dismissEvaluationDialog() {
        _shouldShowEvaluationDialog.value = false
    }

    // CP5.2-A: 重试失败蒸馏
    fun retryArticle(articleId: String) {
        _retryState.value = RetryState.Loading
        viewModelScope.launch {
            runCatching { repository.retryArticle(articleId) }
                .onSuccess { response ->
                    _retryState.value = RetryState.Success(response.retry_count)
                    // 重新加载文章状态
                    loadArticle(articleId)
                }
                .onFailure { e ->
                    _retryState.value = RetryState.Error(e.message ?: "重试失败")
                }
        }
    }

    // CP-DELETE: 删除当前文章（硬删除，后端级联清理蒸馏结果 + 音频文件）。
    fun deleteArticle(articleId: String) {
        if (_deleteState.value is DeleteState.Loading) return
        _deleteState.value = DeleteState.Loading
        pollingJob?.cancel()
        runCatching { playerController.stop() }
        viewModelScope.launch {
            runCatching { repository.deleteArticle(articleId) }
                .onSuccess { _deleteState.value = DeleteState.Success }
                .onFailure { e ->
                    _deleteState.value =
                        DeleteState.Error(friendlyError(e, fallback = "删除失败"))
                }
        }
    }
}

data class ArticleUiState(
    val article: Article? = null,
    val status: DistillStatus = DistillStatus.PENDING,
    val audioUrl: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val pollError: String? = null,
    val pollTimedOut: Boolean = false,
    // CP3.7.0: §1.3 状态响应额外字段（蒸馏完成时填充）
    val tags: List<String> = emptyList(),
    val qualityScore: Double? = null,
    val audioDurationSec: Int? = null,
) {
    /** 兼容性 getter：UiState 不存 article.tags（避免字段重复）；UI 可直接读这里 */
    val tagsIfReady: List<String> get() = tags
    val qualityScoreIfReady: Double? get() = qualityScore
}

// CP5.2-A: retry UI 状态
sealed class RetryState {
    data object Idle : RetryState()
    data object Loading : RetryState()
    data class Success(val retryCount: Int) : RetryState()
    data class Error(val message: String) : RetryState()
}

// CP-DELETE: 删除 UI 状态
sealed class DeleteState {
    data object Idle : DeleteState()
    data object Loading : DeleteState()
    data object Success : DeleteState()
    data class Error(val message: String) : DeleteState()
}