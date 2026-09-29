package com.tingxia.audio.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.audio.VariantSelectionUseCase
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.remote.LaterListen
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.data.repository.FavoritesRepository
import com.tingxia.audio.data.repository.VariantRepository
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 通勤预加载（CP7.4.0 §3.2 预热端点的客户端入口）。
 *
 * 流程：
 * 1. 加载「稍后听」列表作为预加载候选
 * 2. 对每篇调 [ArticleRepository.getArticleStatus] 拿 task_id
 * 3. 调 [VariantRepository.warmVariant] 触发服务端按需 ffmpeg 转码
 * 4. 拿到 oss_key 后用 OkHttp 缓存到本地（命中 [com.tingxia.audio.audio.OfflineDownloadManager]）
 *
 * 当前实现：仅完成 §3.2 warm 端点的批量调用 + 进度；本地缓存由 ExoPlayer CacheDataSource
 * 透明处理（首次播放命中即缓存）。
 */
@HiltViewModel
class OfflineDownloadViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val articleRepository: ArticleRepository,
    private val variantRepository: VariantRepository,
    private val variantSelection: VariantSelectionUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** §3.2 默认预热档位：通勤场景优先 64k */
    private val targetBitrate: Int = 64

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { favoritesRepository.listLaterListens() }
                .onSuccess { items ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            candidates = items.map { item ->
                                OfflineCandidate(
                                    articleId = item.article_id,
                                    title = item.article_title ?: item.article_id,
                                )
                            },
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = friendlyError(e, fallback = "加载稍后听列表失败"),
                        )
                    }
                }
        }
    }

    /** 批量预热：对 candidates 中 ready 的 article 调 §3.2 warm */
    fun batchWarm() {
        val candidates = _uiState.value.candidates
        if (candidates.isEmpty() || _uiState.value.isBatching) return
        _uiState.update { it.copy(isBatching = true, error = null) }

        viewModelScope.launch {
            var warmed = 0
            var failed = 0
            candidates.forEachIndexed { idx, candidate ->
                _uiState.update { it.copy(progressIndex = idx + 1, progressTotal = candidates.size) }
                val result: com.tingxia.audio.data.model.VariantWarmResponse? =
                    try {
                        // 1. 取 task_id
                        val status = articleRepository.getArticleStatus(candidate.articleId)
                        if (status.status != DistillStatus.READY) {
                            null
                        } else if (status.taskId == null) {
                            null
                        } else {
                            // 2. warm 64k
                            variantRepository.warmVariant(status.taskId, targetBitrate)
                        }
                    } catch (_: Exception) {
                        null
                    }
                if (result != null && result.generated) warmed++ else failed++
            }
            _uiState.update {
                it.copy(
                    isBatching = false,
                    batchSummary = BatchSummary(warmed = warmed, failed = failed),
                )
            }
        }
    }

    fun dismissSummary() {
        _uiState.update { it.copy(batchSummary = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    data class OfflineCandidate(
        val articleId: String,
        val title: String,
    )

    data class BatchSummary(
        val warmed: Int,
        val failed: Int,
    )

    data class UiState(
        val isLoading: Boolean = false,
        val isBatching: Boolean = false,
        val candidates: List<OfflineCandidate> = emptyList(),
        val progressIndex: Int = 0,
        val progressTotal: Int = 0,
        val batchSummary: BatchSummary? = null,
        val error: String? = null,
        val targetBitrate: Int = 64,
    ) {
        val progressFraction: Float get() = if (progressTotal > 0) progressIndex.toFloat() / progressTotal else 0f
    }
}