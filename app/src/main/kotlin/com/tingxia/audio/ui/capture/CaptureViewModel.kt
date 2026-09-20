package com.tingxia.audio.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.repository.ArticleRepository
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
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val repository: ArticleRepository,
) : ViewModel() {

    data class UiState(
        val url: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
        val capturedArticleId: String? = null,
        val recentArticles: List<Article> = emptyList(),
        val isRecentLoading: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadRecent()
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
}