package com.tingxia.audio.ui.add

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
 * CP10.4 添加页 ViewModel:复用 [ArticleRepository.createArticle] 加 URL。
 *
 * CP11.0.2 升级:加"最近添加列表"——用户提交后留在页面,看到自己的剪藏记录。
 * 与 [com.tingxia.audio.ui.capture.CaptureViewModel] 功能等价,文案不同。
 *
 * CP11.0.4 P1.2 升级:加 quota 拦截 + quota banner,逻辑与 CaptureViewModel 对齐。
 */
@HiltViewModel
class AddViewModel @Inject constructor(
    private val repository: ArticleRepository,
    private val quotaRepository: QuotaRepository,
) : ViewModel() {

    data class UiState(
        val url: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
        val addedArticleId: String? = null,
        val recentArticles: List<Article> = emptyList(),
        val isRecentLoading: Boolean = false,
        // CP11.0.4 P1.2: quota banner 字段
        val quotaUsed: Int? = null,
        val quotaTotal: Int? = null,
        val quotaRemaining: Int? = null,
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
                android.util.Log.w("AddViewModel", "loadRecent failed: ${e.message}")
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
                android.util.Log.w("AddViewModel", "refreshQuota failed: ${e.message}")
            }
        }
    }

    fun add(onSuccess: (String) -> Unit) {
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

            // CP11.0.4 P1.2: 提交前查 quota,拦截用尽用户
            try {
                val status = quotaRepository.getStatus()
                if (status == QuotaStatus.Exhausted) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        quotaExhausted = true,
                    )
                    refreshQuota()
                    return@launch
                }
            } catch (e: Exception) {
                android.util.Log.w("AddViewModel", "pre-add quota check failed: ${e.message}")
            }

            try {
                val resp = repository.createArticle(url)
                val id = resp.id ?: resp.articleId ?: resp.taskId ?: ""
                android.util.Log.i(
                    "AddViewModel",
                    "add ok: id=$id status=${resp.status} taskId=${resp.taskId}",
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    addedArticleId = id.ifEmpty { null },
                    url = "",
                )
                onSuccess(id)
                loadRecent()
                refreshQuota()
            } catch (e: Exception) {
                android.util.Log.w("AddViewModel", "add failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "添加失败,请稍后再试",
                )
            }
        }
    }

    fun consumeAdded() {
        _uiState.value = _uiState.value.copy(addedArticleId = null)
    }

    fun consumeQuotaExhausted() {
        _uiState.value = _uiState.value.copy(quotaExhausted = false)
    }
}