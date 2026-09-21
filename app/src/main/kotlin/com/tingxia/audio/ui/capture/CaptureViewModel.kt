package com.tingxia.audio.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.QuotaStatus
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.data.repository.QuotaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
        val url = _uiState.value.url.trim()
        if (url.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "请输入链接")
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _uiState.value = _uiState.value.copy(error = "链接必须以 http(s):// 开头")
            return
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
                onSuccess(id)
                loadRecent()
                refreshQuota()   // 提交成功后 quota-1,刷新 banner
            } catch (e: Exception) {
                android.util.Log.w("CaptureViewModel", "capture failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "剪藏失败,请稍后再试",
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
}