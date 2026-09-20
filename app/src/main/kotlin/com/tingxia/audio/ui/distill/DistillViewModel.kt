package com.tingxia.audio.ui.distill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.repository.ArticleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * CP10.4 蒸馏中心 ViewModel:
 * - 拉所有文章,只展示 status=PENDING / FAILED 的(已 READY 的不需要再蒸馏)
 * - 点 "立即蒸馏" → 调 [ArticleRepository.distillArticle](id) 手动重派
 * - 成功 → 该行从列表里移除 + Toast 提示
 */
@HiltViewModel
class DistillViewModel @Inject constructor(
    private val repository: ArticleRepository,
) : ViewModel() {

    data class UiState(
        val pendingArticles: List<Article> = emptyList(),
        val isLoading: Boolean = false,
        val isDistilling: Boolean = false,
        val error: String? = null,
        val toast: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val all = repository.getArticles()
                val pending = all.filter {
                    it.status == DistillStatus.PENDING || it.status == DistillStatus.FAILED
                }
                _uiState.value = _uiState.value.copy(
                    pendingArticles = pending,
                    isLoading = false,
                )
            } catch (e: Exception) {
                android.util.Log.w("DistillViewModel", "load failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "加载失败",
                )
            }
        }
    }

    fun distill(articleId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDistilling = true, error = null)
            try {
                val resp = repository.distillArticle(articleId)
                android.util.Log.i(
                    "DistillViewModel",
                    "distill ok: articleId=$articleId status=${resp.status} taskId=${resp.taskId}",
                )
                _uiState.value = _uiState.value.copy(
                    pendingArticles = _uiState.value.pendingArticles.filter { it.id != articleId },
                    isDistilling = false,
                    toast = "已派蒸馏任务,5-30s 完成",
                )
            } catch (e: Exception) {
                android.util.Log.w("DistillViewModel", "distill failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isDistilling = false,
                    error = e.message ?: "蒸馏失败",
                )
            }
        }
    }

    fun consumeToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }
}