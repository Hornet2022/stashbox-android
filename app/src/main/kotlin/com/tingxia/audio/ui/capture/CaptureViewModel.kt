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
 * 成功回调 [capturedArticleId] 让 UI 弹 "剪藏成功" 提示并返回首页。
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
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun onUrlChanged(value: String) {
        _uiState.value = _uiState.value.copy(url = value, error = null)
    }

    /**
     * 提交剪藏:成功 → 设 capturedArticleId(UI 监听后弹提示 + popBack)
     * 失败 → 设 error(UI Toast 提示)
     */
    fun capture(onSuccess: (Article) -> Unit) {
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
                val article = repository.createArticle(url)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    capturedArticleId = article.id,
                )
                onSuccess(article)
            } catch (e: Exception) {
                android.util.Log.w("CaptureViewModel", "capture failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "剪藏失败,请稍后再试",
                )
            }
        }
    }

    /** 清掉一次性事件(success 已消费) */
    fun consumeCaptured() {
        _uiState.value = _uiState.value.copy(capturedArticleId = null)
    }
}