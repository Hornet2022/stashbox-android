package com.tingxia.audio.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.data.local.PlaylistItemEntity
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.data.repository.PlaylistRepository
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistUiState(
    val items: List<PlaylistItemEntity> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

/**
 * 播放列表（2026-10-23 新增）。
 *
 * 队列的**唯一真相源**是 [PlaylistRepository]（Room），
 * 这里只负责把表里的东西变成界面能用的状态，并把用户的操作发回去。
 */
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val playerController: PlayerController,
    private val articleRepository: ArticleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistUiState())
    val uiState: StateFlow<PlaylistUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observe().collect { items ->
                _uiState.value = _uiState.value.copy(items = items, isLoading = false)
                // 补齐缺地址的条目（见 [backfillMissingMetadata]）
                backfillMissingMetadata(items)
            }
        }
    }

    /**
     * 补齐「加入时还没蒸馏好」的条目。
     *
     * 播放列表存的是**地址快照**（见 PlaylistItemEntity）。如果用户在蒸馏
     * 完成**之前**就把文章加进了列表，那份快照是空的 → 那一篇永远播不了
     * （队列构建时会跳过空地址的条目）。
     *
     * 这里用一次「用户文章列表」请求把缺的补上：文章列表带 audio_url 与
     * duration，一次请求就能补齐所有缺的条目。
     */
    private suspend fun backfillMissingMetadata(items: List<PlaylistItemEntity>) {
        // 缺**地址**或缺**时长**都要补：地址缺了播不了，时长缺了列表显示「—」。
        val missing = items.filter { it.audioUrl.isNullOrBlank() || it.durationSec == null }
        if (missing.isEmpty()) return
        runCatching { articleRepository.getArticles() }
            .onSuccess { articles ->
                val byId = articles.associateBy { it.id }
                for (item in missing) {
                    val article = byId[item.articleId] ?: continue
                    val url = article.audioUrl
                    // 还没蒸馏好 → 地址仍然空着，等下次（时长可以先补上）
                    repository.refreshUrl(
                        articleId = item.articleId,
                        audioUrl = url ?: item.audioUrl,
                        title = article.title,
                        durationSec = article.durationSec ?: item.durationSec,
                    )
                }
            }
            .onFailure { /* 补不上就维持原样：用户还能在详情页重新加入 */ }
    }

    /** 移出播放列表。 */
    fun remove(articleId: String) {
        viewModelScope.launch {
            runCatching { repository.remove(articleId) }
                .onFailure { _uiState.value = _uiState.value.copy(error = friendlyError(it, "移除失败")) }
        }
    }

    /** 上移一位。 */
    fun moveUp(articleId: String) {
        viewModelScope.launch {
            runCatching { repository.moveUp(articleId) }
                .onFailure { _uiState.value = _uiState.value.copy(error = friendlyError(it, "排序失败")) }
        }
    }

    /** 下移一位。 */
    fun moveDown(articleId: String) {
        viewModelScope.launch {
            runCatching { repository.moveDown(articleId) }
                .onFailure { _uiState.value = _uiState.value.copy(error = friendlyError(it, "排序失败")) }
        }
    }

    /** 清空播放列表。 */
    fun clear() {
        viewModelScope.launch {
            runCatching { repository.clear() }
                .onFailure { _uiState.value = _uiState.value.copy(error = friendlyError(it, "清空失败")) }
        }
    }

    /**
     * 从第 [index] 项开始播。
     *
     * 队列由播放列表决定（见 [PlayerController.buildQueue]），
     * 所以这里只要把播放器**切到那一项**即可 —— 上一首/下一首自然就是列表里的。
     */
    fun playFrom(index: Int) {
        val item = _uiState.value.items.getOrNull(index) ?: return
        val url = item.audioUrl
        if (url.isNullOrBlank()) {
            // 文章还没蒸馏好 → 说清楚，而不是按了没反应。
            _uiState.value = _uiState.value.copy(error = "这一篇还没有音频，蒸馏好了才能播")
            return
        }
        playerController.play(
            audioUrl = url,
            title = item.title ?: "",
            articleId = item.articleId,
        )
    }

    /** 错误提示消费掉（避免同一个错误反复弹）。 */
    fun consumeError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
