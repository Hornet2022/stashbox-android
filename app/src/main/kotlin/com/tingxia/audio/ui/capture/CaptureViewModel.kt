package com.tingxia.audio.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.model.QuotaStatus
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.data.repository.QuotaRepository
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
                // P0-3 兜底：服务端 submit_article 现在会自动派蒸馏；这里保留 trigger_distill
                // 作为 client-side 兜底（即使服务端 B1 漏修，客户端也能保证 taskId 入库可轮询）。
                // 端点对已存在 task 的文章是 idempotent 的（不重复扣配额），安全。
                if (id.isNotEmpty()) {
                    try {
                        val triggerResp = repository.distillArticle(id)
                        android.util.Log.i(
                            "CaptureViewModel",
                            "distill trigger ok: taskId=${triggerResp.taskId} status=${triggerResp.status}",
                        )
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "CaptureViewModel",
                            "distill trigger failed (服务端可能已自动派): ${e.message}",
                        )
                    }
                }
                onSuccess(id)
                loadRecent()
                refreshQuota()   // 提交成功后 quota-1,刷新 banner
                // P0-4：启动实时轮询，把这条新文章从 pending/distilling 自动更新到 ready
                if (id.isNotEmpty()) {
                    startStatusPolling(id)
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
     * P0-4：实时轮询刚剪藏的文章状态。
     * - 每 3s 拉一次 getArticle(id)，更新 recentArticles 里对应条目（用户可见 statusBadge）
     * - status=ready 或 30 次超时（90s）停止
     * - 同一 articleId 多次启动会自动取消旧的（防泄漏）
     */
    private fun startStatusPolling(articleId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            var attempts = 0
            while (attempts < MAX_POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                attempts++
                try {
                    val updated = repository.getArticle(articleId)
                    updateRecentArticle(updated)
                    if (updated.status == DistillStatus.READY ||
                        updated.status == DistillStatus.LISTENED
                    ) {
                        android.util.Log.i(
                            "CaptureViewModel",
                            "poll: article=$articleId reached ${updated.status} after $attempts attempts",
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
                        "poll: article=$articleId attempt=$attempts failed: ${e.message}",
                    )
                }
            }
            android.util.Log.w(
                "CaptureViewModel",
                "poll: article=$articleId timed out after $attempts attempts",
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
        private const val POLL_INTERVAL_MS = 3000L
        private const val MAX_POLL_ATTEMPTS = 30  // 3s × 30 = 90s 后超时
    }
}