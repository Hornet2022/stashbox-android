package com.tingxia.audio.ui.articles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.repository.ArticleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * 详情页 ViewModel。
 *
 * 数据流：
 * 1. [loadArticle] → GET /api/v1/articles/{id} 拿 article（含 taskId + status）
 * 2. 若 status != ready 且有 taskId → [startPolling] 每 [POLL_INTERVAL_MS] 秒
 *    GET /api/v1/distill/{task_id}，直到 status == ready / failed
 * 3. ready → 拉 audio_url 并显示播放器；failed/超时 → 标记错误
 */
@HiltViewModel
class ArticleDetailViewModel @Inject constructor(
    private val repository: ArticleRepository,
    private val playerController: PlayerController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArticleUiState())
    val uiState: StateFlow<ArticleUiState> = _uiState.asStateFlow()

    // CP5.2-A: retry 状态
    private val _retryState = MutableStateFlow<RetryState>(RetryState.Idle)
    val retryState: StateFlow<RetryState> = _retryState.asStateFlow()

    fun loadArticle(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val article = repository.getArticle(id)
                // 文章已就绪（或已听）时，首屏直接取 article.audioUrl，无需轮询
                val initialAudioUrl =
                    if (article.status == DistillStatus.READY ||
                        article.status == DistillStatus.LISTENED
                    ) {
                        article.audioUrl
                    } else {
                        null
                    }
                _uiState.update {
                    it.copy(
                        article = article,
                        status = article.status,
                        audioUrl = initialAudioUrl,
                        isLoading = false,
                    )
                }
                // CP4.4：蒸馏已就绪，直接起播（audio_url 已就绪）
                // CP4.5：把 article.title/source 传给 play()，让锁屏 UI 显示标题/作者
                initialAudioUrl?.let { url ->
                    playerController.play(url, title = article.title ?: "", author = article.source)
                }
                if (article.taskId != null &&
                    article.status != DistillStatus.READY &&
                    article.status != DistillStatus.LISTENED
                ) {
                    startPolling(article.taskId, article.id)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    private fun startPolling(taskId: String, articleId: String) {
        viewModelScope.launch {
            var attempts = 0
            while (attempts < MAX_POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                attempts++
                try {
                    val status = repository.getDistillStatus(taskId)
                    when (status) {
                        DistillStatus.READY -> {
                            val url = runCatching { repository.getAudioUrl(articleId) }.getOrNull()
                            _uiState.update { it.copy(status = DistillStatus.READY, audioUrl = url) }
                            // CP4.4：蒸馏完成，起播；CP4.5：带 title/source 供锁屏显示
                            val art = _uiState.value.article
                            url?.let {
                                playerController.play(
                                    it,
                                    title = art?.title ?: "",
                                    author = art?.source,
                                )
                            }
                            return@launch
                        }
                        DistillStatus.FAILED -> {
                            _uiState.update { it.copy(status = DistillStatus.FAILED, pollError = "蒸馏失败") }
                            return@launch
                        }
                        else -> _uiState.update { it.copy(status = status) }
                    }
                } catch (e: Exception) {
                    _uiState.update { it.copy(pollError = e.message ?: "轮询失败") }
                    return@launch
                }
            }
            // 超过最大尝试次数仍未 ready → 超时
            _uiState.update { it.copy(pollTimedOut = true, pollError = "蒸馏超时（>90s）") }
        }
    }

    // CP5.2-A: 重试失败蒸馏
    fun retryArticle(articleId: String) {
        _retryState.value = RetryState.Loading
        viewModelScope.launch {
            runCatching { repository.retryArticle(articleId) }
                .onSuccess { response ->
                    _retryState.value = RetryState.Success(response.retry_count)
                    // 重新加载文章状态
                    loadArticle(articleId)
                }
                .onFailure { e ->
                    _retryState.value = RetryState.Error(e.message ?: "重试失败")
                }
        }
    }

    companion object {
        /** 轮询间隔：3 秒 */
        const val POLL_INTERVAL_MS: Long = 3000L

        /** 最大轮询次数：30 次 ≈ 90 秒后超时 */
        const val MAX_POLL_ATTEMPTS: Int = 30
    }
}

data class ArticleUiState(
    val article: Article? = null,
    val status: DistillStatus = DistillStatus.PENDING,
    val audioUrl: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val pollError: String? = null,
    val pollTimedOut: Boolean = false,
)

// CP5.2-A: retry UI 状态
sealed class RetryState {
    data object Idle : RetryState()
    data object Loading : RetryState()
    data class Success(val retryCount: Int) : RetryState()
    data class Error(val message: String) : RetryState()
}
