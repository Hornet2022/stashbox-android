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

    // CP-TAG-FILTER：当前过滤的 tag slug（null=全量）。UI 顶部 chip 观察此 state。
    private val _activeTag = MutableStateFlow<String?>(null)
    val activeTag: StateFlow<String?> = _activeTag.asStateFlow()

    fun loadArticles() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val tag = _activeTag.value
                _articles.value = if (tag != null) {
                    repository.getArticlesByTag(tag)
                } else {
                    repository.getArticles()
                }
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

    /** CP-TAG-FILTER：切换 tag 过滤；传入 null 恢复全量 */
    fun setActiveTag(tag: String?) {
        if (_activeTag.value == tag) return
        _activeTag.value = tag
        loadArticles()
    }

    /**
     * 主动重载列表 —— 不显示 loading indicator（避免列表上闪一下）。
     * 列表进入时由 LaunchedEffect 调用，用于拉取最新文章。
     */
    fun reloadArticles() {
        viewModelScope.launch {
            try {
                val tag = _activeTag.value
                _articles.value = if (tag != null) {
                    repository.getArticlesByTag(tag)
                } else {
                    repository.getArticles()
                }
                _error.value = null
                android.util.Log.i("ArticleListViewModel", "reloadArticles ok (${_articles.value.size} items, tag=$tag)")
            } catch (e: Exception) {
                android.util.Log.w("ArticleListViewModel", "reloadArticles failed: ${e.message}")
                _error.value = e.message ?: "重载失败"
            }
        }
    }

    // CP-DELETE: 长按删除。成功 → 本地列表即时移除该行（不整表刷新，避免闪动）；
    // 失败 → _error 提示（UI 侧 observe error 弹 Toast）。
    private val _deletingId = MutableStateFlow<String?>(null)
    val deletingId: StateFlow<String?> = _deletingId.asStateFlow()

    fun deleteArticle(id: String) {
        if (_deletingId.value != null) return // 串行防重复点
        _deletingId.value = id
        viewModelScope.launch {
            try {
                repository.deleteArticle(id)
                _articles.value = _articles.value.filterNot { it.id == id }
            } catch (e: Exception) {
                android.util.Log.w("ArticleListViewModel", "deleteArticle failed: ${e.message}")
                _error.value = e.message ?: "删除失败"
            } finally {
                _deletingId.value = null
            }
        }
    }
}
