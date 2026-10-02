package com.tingxia.audio.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.audio.OfflineDownloadManager
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
 * 4. [VariantSelectionUseCase.pick] 取该档可下载 URL
 * 5. [OfflineDownloadManager.download] 真正把文件落到本机
 *
 * ⚠️ 2026-10-03 修：原来第 4/5 步**根本不存在** —— batchWarm 全程只调
 * `warmVariant`（服务端转码），`OfflineDownloadManager` 连注入都没有。
 * 而页面文案写着「预热**到本机**」「通勤无网时也能秒开」，弹窗还报
 * 「预热完成 成功 N 篇」—— 设备上零字节，用户据此进地铁必然打不开。
 *
 * 原 KDoc 的辩解是「本地缓存由 ExoPlayer CacheDataSource 透明处理
 * （首次播放命中即缓存）」，但那只在**播放之后**才生效，而这页存在的
 * 全部意义就是播放**之前**下好。承诺和机制是矛盾的。
 */
// OfflineDownloadManager 是 media3 `@UnstableApi` 家族（见该类的说明）。
// 在定义处加 opt-in 标注对 lint 无效（kotlin.OptIn 和 androidx.OptIn 都试过，
// error 数不变），所以按 lint 自己的建议在**调用方**标注。
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@HiltViewModel
class OfflineDownloadViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val articleRepository: ArticleRepository,
    private val variantRepository: VariantRepository,
    private val variantSelection: VariantSelectionUseCase,
    private val offlineDownloadManager: OfflineDownloadManager,
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

    /** 批量预热：对 candidates 中 ready 的 article 调 §3.2 warm，并**落盘到本机** */
    fun batchWarm() {
        val candidates = _uiState.value.candidates
        if (candidates.isEmpty() || _uiState.value.isBatching) return
        _uiState.update { it.copy(isBatching = true, error = null) }

        viewModelScope.launch {
            var transcoded = 0
            var downloaded = 0
            var failed = 0
            val scenario = variantSelection.detectScenario()

            candidates.forEachIndexed { idx, candidate ->
                _uiState.update { it.copy(progressIndex = idx + 1, progressTotal = candidates.size) }
                val ok = runCatching {
                    // 1. 取 task_id
                    val status = articleRepository.getArticleStatus(candidate.articleId)
                    if (status.status != DistillStatus.READY) return@runCatching null
                    val taskId = status.taskId ?: return@runCatching null

                    // 2. 触发服务端转码到通勤档
                    variantRepository.warmVariant(taskId, targetBitrate)
                    transcoded++

                    // 3. 取该档 URL —— warm 只保证服务端有货，URL 得再协商一次
                    val variant = variantSelection.pick(taskId, scenario)
                    val url = variant.url ?: return@runCatching null

                    // 4. 真正下载到本机（这才是「通勤无网能秒开」的前提）
                    if (offlineDownloadManager.download(candidate.articleId, url, candidate.title)) {
                        downloaded++
                    }
                    url
                }.getOrNull()

                if (ok == null) failed++
            }
            _uiState.update {
                it.copy(
                    isBatching = false,
                    batchSummary = BatchSummary(
                        transcoded = transcoded,
                        downloaded = downloaded,
                        failed = failed,
                    ),
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
        /** 服务端已转码出通勤档的数量 */
        val transcoded: Int,
        /** **已真正落盘到本机**的数量 —— 这个数才是「离线可听」的保证 */
        val downloaded: Int,
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