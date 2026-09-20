package com.tingxia.audio.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * CP10.5:成功回调改回 (String) -> Unit(articleId 本身),而非 (Article) -> Unit。
 * 后端响应壳 [com.tingxia.audio.data.model.CreateArticleResponse] 字段全 nullable,
 * 即便 id 缺失 UI 也能拿到 taskId/空串兜底,不再被反序列化阻塞。
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
     *
     * 回调 [onSuccess] 传 articleId(后端字段缺失时为 ""),不复用 Article。
     */
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
                )
                onSuccess(id)
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