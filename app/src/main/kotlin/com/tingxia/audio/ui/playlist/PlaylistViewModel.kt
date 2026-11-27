package com.tingxia.audio.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.data.local.PlaylistItemEntity
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.remote.ApiException
import com.tingxia.audio.data.remote.isAudioNotReady
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
     * 完成**之前**就把文章加进了列表，那份快照是空的 → 那一篇播不了。
     *
     * ## 为什么按 id 单篇取，而不是拉一次文章列表
     *
     * 原来这里调 `getArticles()` 再在内存里按 id 匹配 —— 有两个洞：
     *
     * 1. **分页截断**：`GET /api/v1/articles` 默认 `limit=20`（只返回最近
     *    20 篇）。播放列表里那篇若是老文章，就**永远匹配不上** ⇒ 地址永远
     *    补不上 ⇒ 用户永远看到「这一篇还没有音频」。
     * 2. **快照是一次性的**：列表接口给的是**那一刻**的状态。补齐请求发出时
     *    若文章还在蒸馏，audio_url 仍为空 → 补不上；之后**没有任何机制**会在
     *    蒸馏完成时再触发一次（蒸馏完成不写本机 Room 表 ⇒ observer 不 emit）。
     *
     * ⇒ 改成按 id 取单篇地址（`GET /articles/{id}/audio-url`）：不受分页限制、
     *   数据是当下的、没就绪时后端直接 404（runCatching 兜住 ⇒ 不写、下一轮再来）。
     */
    private suspend fun backfillMissingMetadata(items: List<PlaylistItemEntity>) {
        // 缺**地址**或缺**时长**都要补：地址缺了播不了，时长缺了列表显示「—」。
        val missing = items.filter { it.audioUrl.isNullOrBlank() || it.durationSec == null }
        if (missing.isEmpty()) return
        for (item in missing) {
            // 还没蒸馏好 → 后端 404 → 兜住、维持原样，等下次。
            val resp = runCatching { articleRepository.getAudioUrl(item.articleId) }.getOrNull()
                ?: continue
            repository.refreshUrl(
                articleId = item.articleId,
                audioUrl = resp.audio_url,
                // 标题用**已有快照**：加入播放列表时就存了（见 ArticleDetailViewModel
                // .addToPlaylist），这里补不出来也不该补 —— 别为了显示去多打一次请求。
                title = item.title,
                durationSec = resp.durationSec ?: item.durationSec,
            )
        }
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
     *
     * ## 快照为空时**现取**地址（2026-11-25 修）
     *
     * 播放列表存的是地址**快照**（见 [PlaylistRepository] 的设计契约：
     * 「只存 id，播放时现取地址」）。用户在蒸馏完成**之前**把文章加进列表 ⇒
     * 快照是空的；而蒸馏完成**不会**驱动本机表变化 ⇒ 补齐逻辑不会被触发
     * ⇒ 用户回到列表点播放，拿到的还是空快照 ⇒ 「这一篇还没有音频」。
     *
     * ⇒ 这里兜底：**快照空就现取一次**。取到就播（并把新地址写回快照），
     *   取不到（还在蒸馏）才提示。
     */
    fun playFrom(index: Int) {
        val item = _uiState.value.items.getOrNull(index) ?: return
        val url = item.audioUrl
        if (!url.isNullOrBlank()) {
            playerController.play(
                audioUrl = url,
                title = item.title ?: "",
                articleId = item.articleId,
            )
            return
        }
        viewModelScope.launch {
            // C2（播放列表「可播」闭环契约 §3）：失败必须走**既有的分级判据**。
            //
            // 原写法自己写了一句笼统的「这一篇还没有音频，蒸馏好了才能播」——
            // 但服务端 `audio-url` 未就绪时返的是 **404 + code 40400**
            // （既有判据 `ApiException.isAudioNotReady` = "还没好，继续轮询"），
            // 且 `ErrorMessages` 里已有分级文案「音频还在蒸馏中，请稍候」。
            // ⇒ 自己重写文案 = 把"还没好"说成"没有" ⇒ 用户以为永久不可用。
            val result = runCatching { articleRepository.getAudioUrl(item.articleId) }
            val fetched = result.getOrNull()
            if (fetched == null || fetched.audio_url.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    error = resolveUnavailableMessage(item.articleId, result.exceptionOrNull()),
                )
                return@launch
            }
            repository.refreshUrl(
                articleId = item.articleId,
                audioUrl = fetched.audio_url,
                title = item.title,
                durationSec = fetched.durationSec ?: item.durationSec,
            )
            playerController.play(
                audioUrl = fetched.audio_url,
                title = item.title ?: "",
                articleId = item.articleId,
            )
        }
    }

    /** 错误提示消费掉（避免同一个错误反复弹）。 */
    fun consumeError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * 音频取不到时的**分级**文案（C2）。
     *
     * ## 为什么不能只回一句「这一篇还没有音频」
     *
     * 服务端 `audio-url` 在"没 ready"时一律 404 + code 40400 —— 但**没 ready 有两种**：
     * ① 还在蒸馏（等一会儿就好）；② 蒸馏失败（等多久都没用）。
     * 一句笼统的"没有音频"会把①说成② ⇒ 用户以为永久不可用。
     *
     * ⇒ 命中 `isAudioNotReady` 时**再查一次 `/articles/{id}/status`**（既有端点），
     * 用 `status` + `stage` 把两种情况分开，并给出**能做什么**的下一步。
     *
     * 代价：多一次 GET（仅在取不到地址时）。换来的是文案不说假话。
     */
    private suspend fun resolveUnavailableMessage(articleId: String, error: Throwable?): String {
        val fallback = "这一篇还没有音频，蒸馏好了才能播"
        val apiError = error as? ApiException
        if (apiError?.isAudioNotReady == true) {
            val status = runCatching { articleRepository.getArticleStatus(articleId) }.getOrNull()
            return when (status?.status) {
                DistillStatus.FAILED ->
                    "这篇没能生成音频，可以到文章页重新蒸馏"
                DistillStatus.DISTILLING -> when (status.stage) {
                    "tts" -> "正在合成语音，稍等一会儿就能听"
                    "rewrite" -> "正在改写稿子，稍等一会儿"
                    "concat" -> "正在拼接音频，马上就好"
                    else -> "音频还在蒸馏中，请稍候"
                }
                DistillStatus.PENDING -> "音频还在蒸馏中，请稍候"
                // status 也拿不到（网络/服务端抖动）⇒ 退回"还没好"的说法，
                // 但**不说"没有"** —— 未就绪时最诚实的说法就是"还没好"。
                else -> "音频还在蒸馏中，请稍候"
            }
        }
        return error?.let { friendlyError(it, fallback) } ?: fallback
    }
}
