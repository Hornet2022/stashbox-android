package com.tingxia.audio.ui.add

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
 * CP10.4 添加页 ViewModel:复用 [ArticleRepository.createArticle] 加 URL。
 *
 * CP11.0.2 升级:加"最近添加列表"——用户提交后留在页面,看到自己的剪藏记录。
 * 与 [com.tingxia.audio.ui.capture.CaptureViewModel] 功能等价,文案不同。
 */
@HiltViewModel
class AddViewModel @Inject constructor(
    private val repository: ArticleRepository,
) : ViewModel() {

    data class UiState(
        val url: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
        val addedArticleId: String? = null,
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
                android.util.Log.w("AddViewModel", "loadRecent failed: ${e.message}")
                _uiState.value = _uiState.value.copy(isRecentLoading = false)
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
                    url = "",  // 清空输入框
                )
                onSuccess(id)
                // 刷新最近列表(用户能看到刚添加的)
                loadRecent()
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
}