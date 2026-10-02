package com.tingxia.audio.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.model.QuotaStatus
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.data.repository.QuotaRepository
import com.tingxia.audio.data.sync.PrefetchScheduler
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * CP10.4 剪藏页 ViewModel:用户粘贴 URL → [ArticleRepository.createArticle] → 后端自动派蒸馏。
 *
 * CP11.0.2 升级:加"最近剪藏列表"——提交后留在页面看到剪藏记录。
 *
 * CP11.0.4 P1.2 升级:加 quota 拦截 — 配额用尽时不让用户提交,通过 [quotaExhausted] 通知 UI 跳 Paywall。
 * 同时在 [UiState] 暴露 quota 字段,UI 层用 QuotaBanner 渲染橙色提示条。
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val repository: ArticleRepository,
    private val quotaRepository: QuotaRepository,
    private val prefetchScheduler: PrefetchScheduler,
) : ViewModel() {

    data class UiState(
        val url: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
        val capturedArticleId: String? = null,
        val recentArticles: List<Article> = emptyList(),
        val isRecentLoading: Boolean = false,
        // CP11.0.4 P1.2: quota banner 字段(Healthy 时 UI 不显示)
        val quotaUsed: Int? = null,
        val quotaTotal: Int? = null,
        val quotaRemaining: Int? = null,
        // 配额用尽事件(一次性,UI observe 到 true 后调 consumeQuotaExhausted)
        val quotaExhausted: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // P0-4：实时轮询新剪藏文章状态，避免切走再回来才能看到 ready
    private var pollingJob: Job? = null

    init {
        loadRecent()
        refreshQuota()
    }

    fun onUrlChanged(value: String) {
        _uiState.value = _uiState.value.copy(url = value, error = null)
    }

    fun loadRecent() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRecentLoading = true)
            try {
                val list = repository.getArticles()
                _uiState.value = _uiState.value.copy(
                    isRecentLoading = false,
                    recentArticles = list.take(20),
                )
            } catch (e: Exception) {
                android.util.Log.w("CaptureViewModel", "loadRecent failed: ${e.message}")
                _uiState.value = _uiState.value.copy(isRecentLoading = false)
            }
        }
    }

    fun refreshQuota() {
        viewModelScope.launch {
            try {
                val q = quotaRepository.getQuota()
                _uiState.value = _uiState.value.copy(
                    quotaUsed = q.usedQuota,
                    quotaTotal = q.monthlyQuota,
                    quotaRemaining = q.remaining,
                )
            } catch (e: Exception) {
                android.util.Log.w("CaptureViewModel", "refreshQuota failed: ${e.message}")
                // 失败不更新(保持 null,UI 不显示 banner)
            }
        }
    }

    fun capture(onSuccess: (String) -> Unit) {
        val rawUrl = _uiState.value.url.trim()
        if (rawUrl.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "请输入链接")
            return
        }
        // 自动补全协议头：用户从微信/抖音等分享复制常漏 https://
        val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            "https://$rawUrl"
        }
        if (url != rawUrl) {
            _uiState.value = _uiState.value.copy(url = url)
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // CP11.0.4 P1.2: 提交前再查一次 quota,拦截用尽用户
            try {
                val status = quotaRepository.getStatus()
                if (status == QuotaStatus.Exhausted) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        quotaExhausted = true,
                    )
                    refreshQuota()    // 刷新 banner 数字
                    return@launch
                }
            } catch (e: Exception) {
                // quota 查询失败不阻塞提交(放行)
                android.util.Log.w("CaptureViewModel", "pre-capture quota check failed: ${e.message}")
            }

            try {
                val resp = repository.createArticle(url)
                val id = resp.id ?: resp.articleId ?: resp.taskId ?: ""
                android.util.Log.i(
                    "CaptureViewModel",
                    "capture ok: id=$id status=${resp.status} taskId=${resp.taskId}",
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    capturedArticleId = id.ifEmpty { null },
                    url = "",
                )
                // ⚠️ 这里**不要**再补一次 distillArticle()。服务端 submit_article
                // 已经自动派发（content-service main.py 里 trigger_distill 紧跟建文章），
                // 客户端再调一次等于**入队两个独立的 Arq job**。
                //
                // 原注释写的「端点对已存在 task 的文章是 idempotent 的（不重复扣配额），安全」
                // 只对了一半：配额确实有守卫（所以没扣两次钱），但**入队没有**。
                // 带「已在跑就不再入队」守卫的是另一个端点 `/api/v1/distill/start`，
                // 而这个客户端调的 `/api/v1/articles/{id}/distill` 无条件 enqueue。
                //
                // 实测代价（华为 JEF-AN20，2026-10-02，剪藏一篇 838 字文章）：
                //   10:53:57 job A 开始 TTS（7 段）
                //   11:04:58 A 完成 → 11:04:59 arq_distill_completed
                //   11:04:59 job B 被取走（入队于 10:53:42，排队 delayed=676.97s）
                //   11:05:13 B 重新 LLM 改写 + 全量 TTS（8 段，文字还不一样）
                // → 单篇总耗时翻倍（~23 分钟 vs ~12），本机算力和 LLM 费用双倍，
                //   而第一遍的音频被整个丢弃。
                //
                // taskId 轮询也不需要它：createArticle 的响应里已经带 taskId/status。
                onSuccess(id)
                loadRecent()
                refreshQuota()   // 提交成功后 quota-1,刷新 banner
                if (id.isNotEmpty()) {
                    // 前台：刷新列表上的状态徽标
                    startStatusPolling(id)
                    // 后台：跨进程盯到就绪并发通知 —— 用户切走 app 也能收到「可以听了」。
                    // 这是「文章好了」唯一的触达通路：没有推送通道，push_notifications 表 0 行。
                    prefetchScheduler.watchDistill(id)
                }
            } catch (e: Exception) {
                android.util.Log.w("CaptureViewModel", "capture failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = friendlyError(e, fallback = "剪藏失败，请稍后再试"),
                )
            }
        }
    }

    fun consumeCaptured() {
        _uiState.value = _uiState.value.copy(capturedArticleId = null)
    }

    /** UI 收到 [quotaExhausted]=true 后调这个,避免重复跳 Paywall */
    fun consumeQuotaExhausted() {
        _uiState.value = _uiState.value.copy(quotaExhausted = false)
    }

    /**
     * 实时轮询刚剪藏的文章状态（前台，让列表的 statusBadge 跟着变）。
     *
     * - 前 2 分钟 3 秒一次，之后 10 秒一次
     * - status=ready / failed 即停
     * - 同一 articleId 多次启动会自动取消旧的（防泄漏）
     *
     * ⚠️ 上限从 30 次（90 秒）放宽到 45 分钟。原来的 90 秒不是「异常兜底」，
     * 是**每一次剪藏都会走到的正常路径** —— 实测单篇真实耗时 11 分 40 秒
     *（LLM 改写 14s + TTS 分 7 段合成 11 分钟，2026-10-02 实机），
     * 用户 100% 会看到「蒸馏超时（>90s）」，而内容其实还在正常生成。
     *
     * 这里只负责**界面上的状态刷新**；「文章好了」的通知由
     * [com.tingxia.audio.data.sync.DistillReadyWorker] 负责（跨进程存活，
     * 用户切走 app 也能收到）。两者分工：前台看得见变化，后台收得到提醒。
     */
    private fun startStatusPolling(articleId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            var waited = 0L
            while (System.currentTimeMillis() - startedAt < MAX_POLL_MS) {
                val interval = if (waited < POLL_FAST_PHASE_MS) POLL_FAST_MS else POLL_SLOW_MS
                delay(interval)
                waited += interval
                try {
                    val updated = repository.getArticle(articleId)
                    updateRecentArticle(updated)
                    if (updated.status == DistillStatus.READY ||
                        updated.status == DistillStatus.LISTENED
                    ) {
                        android.util.Log.i(
                            "CaptureViewModel",
                            "poll: article=$articleId reached ${updated.status} after $waited ms",
                        )
                        return@launch
                    }
                    if (updated.status == DistillStatus.FAILED) {
                        android.util.Log.w(
                            "CaptureViewModel",
                            "poll: article=$articleId distill FAILED",
                        )
                        return@launch
                    }
                } catch (e: Exception) {
                    android.util.Log.w(
                        "CaptureViewModel",
                        "poll: article=$articleId after $waited ms failed: ${e.message}",
                    )
                }
            }
            android.util.Log.w(
                "CaptureViewModel",
                "poll: article=$articleId gave up after ${System.currentTimeMillis() - startedAt} ms",
            )
        }
    }

    /** 替换 recentArticles 里 id 匹配的条目（保留顺序） */
    private fun updateRecentArticle(updated: Article) {
        val current = _uiState.value.recentArticles
        val idx = current.indexOfFirst { it.id == updated.id }
        if (idx < 0) return
        val newList = current.toMutableList().apply { this[idx] = updated }
        _uiState.value = _uiState.value.copy(recentArticles = newList)
    }

    /** UI 主动离开 CaptureScreen 时调用，避免 ViewModel 销毁前轮询还在跑 */
    fun stopStatusPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * CP-DELETE：删除最近剪藏里的文章（硬删除，后端级联清理蒸馏结果+音频）。
     * - 若正在轮询这条 → 先取消轮询（文章已删，继续 GET 只会报错）
     * - 成功 → recentArticles 本地即时移除；失败 → error 走页面 Toast
     */
    private val _deletingId = MutableStateFlow<String?>(null)
    val deletingId: StateFlow<String?> = _deletingId.asStateFlow()

    fun deleteArticle(id: String) {
        if (_deletingId.value != null) return // 串行防重复点
        _deletingId.value = id
        pollingJob?.cancel()
        pollingJob = null
        viewModelScope.launch {
            try {
                repository.deleteArticle(id)
                _uiState.value = _uiState.value.copy(
                    recentArticles = _uiState.value.recentArticles.filterNot { it.id == id },
                )
            } catch (e: Exception) {
                android.util.Log.w("CaptureViewModel", "deleteArticle failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    error = friendlyError(e, fallback = "删除失败"),
                )
            } finally {
                _deletingId.value = null
            }
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** 前 2 分钟 3 秒一次：这段时间用户盯着屏幕，状态刷新要跟手。 */
        private const val POLL_FAST_MS = 3_000L
        private const val POLL_FAST_PHASE_MS = 2 * 60 * 1000L
        /** 之后 10 秒一次：12 分钟总共约 76 次请求，不是 240 次。 */
        private const val POLL_SLOW_MS = 10_000L
        /** 45 分钟。实测 12 分钟，这是 3.75 倍余量。 */
        private const val MAX_POLL_MS = 45 * 60 * 1000L
    }
}