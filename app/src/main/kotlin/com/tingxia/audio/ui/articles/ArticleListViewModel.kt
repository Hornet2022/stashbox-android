package com.tingxia.audio.ui.articles

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
 * 列表页 ViewModel：持有文章列表 StateFlow，加载时拉取 [ArticleRepository.getArticles]。
 */
@HiltViewModel
class ArticleListViewModel @Inject constructor(
    private val repository: ArticleRepository,
) : ViewModel() {

    private val _articles = MutableStateFlow<List<Article>>(emptyList())
    val articles: StateFlow<List<Article>> = _articles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // CP7.5: 加 error state,500/网络异常时降级空态不 crash。
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadArticles() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _articles.value = repository.getArticles()
            } catch (e: Exception) {
                // 软降级:不 throw,设 error 让 UI 显示空态/重试按钮。
                android.util.Log.w("ArticleListViewModel", "loadArticles failed (soft-fail): ${e.message}")
                _articles.value = emptyList()
                _error.value = e.message ?: "加载失败"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
