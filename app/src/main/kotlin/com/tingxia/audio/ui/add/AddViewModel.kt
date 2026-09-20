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
 * 与 [com.tingxia.audio.ui.capture.CaptureViewModel] 功能等价,文案不同("添加文章" vs "剪藏文章")。
 * 业务上两条独立路径(HomeScreen TopAppBar 添加按钮 vs 2x2 grid 剪藏卡),ViewModel 各自持有独立 state。
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
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun onUrlChanged(value: String) {
        _uiState.value = _uiState.value.copy(url = value, error = null)
    }

    fun add(onSuccess: (Article) -> Unit) {
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
                    addedArticleId = article.id,
                )
                onSuccess(article)
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