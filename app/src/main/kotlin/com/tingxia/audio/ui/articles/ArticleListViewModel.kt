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

    fun loadArticles() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _articles.value = repository.getArticles()
            } finally {
                _isLoading.value = false
            }
        }
    }
}
