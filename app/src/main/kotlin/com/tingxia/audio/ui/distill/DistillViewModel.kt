package com.tingxia.audio.ui.distill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * CP10.4 蒸馏中心 ViewModel:
 * - 拉所有文章,只展示 status=PENDING / FAILED 的(已 READY 的不需要再蒸馏)
 * - 点 "立即蒸馏" → 调 [ArticleRepository.distillArticle](id) 手动重派
 * - 成功 → 启动**真轮询**：每 3s 查 [ArticleRepository.getDistillStatus](taskId)，
 *   直到 READY / FAILED（参照 ArticleDetailViewModel.startPolling）。
 *
 * 修正（CP11 收口）：移除原先 30s 假倒计时 —— 假进度会无条件报"蒸馏完成"，
 * 即使后端实际失败也误判成功。现在以**后端真实状态**为准。
 */
@HiltViewModel
class DistillViewModel @Inject constructor(
    private val repository: ArticleRepository,
) : ViewModel() {

    /**
     * 某篇文章正在轮询中的标记(per-article key)。
     * 文章被点 → 进入 in-progress → UI 显示"蒸馏中"不确定进度条 + 按钮 disable。
     */
    data class DistillInProgress(
        val articleId: String,
    )

    data class UiState(
        val pendingArticles: List<Article> = emptyList(),
        val distillingArticles: List<Article> = emptyList(),
        val failedArticles: List<Article> = emptyList(),
        val isLoading: Boolean = false,
        val isDistilling: Boolean = false,
        val error: String? = null,
        val toast: String? = null,
        // 轮询中的文章(可同时多个)
        val inProgress: Map<String, DistillInProgress> = emptyMap(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // 防止多个 distill job 撞同一文章
    private val pollJobs: MutableMap<String, Job> = mutableMapOf()

    /**
     * 全局并发轮询上限（2026-10-03）。
     *
     * `pollJobs` 只按 articleId 去重，**没有全局上限** —— 点 N 篇就是 N 个
     * 独立轮询器同时跑，15s 一次，App 切后台（`viewModelScope` 不随息屏取消）
     * 还在继续烧。
     *
     * 上限 3 是权衡：超过这个数的任务不启动轮询，UI 仍标记为「处理中」，
     * 由服务端独立跑完，用户下次进页面直接看到结果（列表页不依赖轮询器）。
     */
    private val maxConcurrentPolls = 3

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val all = repository.getArticles()
                // CP-DISTILL：三段式 —— pending（等待蒸馏）/ distilling（处理中）/ failed（待重试）
                // 与「最近剪窗」的轮询去重：这里只展示后端真实 status，不主动轮询。
                // 蒸馏状态变化由 ArticleDetailViewModel.startPolling 负责。
                val pending = all.filter { it.status == DistillStatus.PENDING }
                val distilling = all.filter { it.status == DistillStatus.DISTILLING }
                val failed = all.filter { it.status == DistillStatus.FAILED }
                _uiState.value = _uiState.value.copy(
                    pendingArticles = pending,
                    distillingArticles = distilling,
                    failedArticles = failed,
                    isLoading = false,
                )
            } catch (e: Exception) {
                android.util.Log.w("DistillViewModel", "load failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = friendlyError(e, fallback = "加载失败"),
                )
            }
        }
    }

    fun distill(articleId: String) {
        // 已在轮询中,忽略重复点击
        if (pollJobs[articleId]?.isActive == true) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDistilling = true, error = null)
            try {
                val resp = repository.distillArticle(articleId)
                android.util.Log.i(
                    "DistillViewModel",
                    "distill ok: articleId=$articleId taskId=${resp.taskId}",
                )
                val taskId = resp.taskId
                if (taskId.isNullOrBlank()) {
                    // 后端未返回 taskId：按已提交处理，直接移出待蒸馏列表
                    _uiState.value = _uiState.value.copy(
                        isDistilling = false,
                        pendingArticles = _uiState.value.pendingArticles.filter { it.id != articleId },
                        toast = "已提交蒸馏",
                    )
                    return@launch
                }
                // CP-DISTILL：从 pending 桶移出，加入 distilling 桶（让用户在蒸馏中心能看到「处理中」）
                val moved = _uiState.value.pendingArticles.firstOrNull { it.id == articleId }
                    ?: _uiState.value.failedArticles.firstOrNull { it.id == articleId }
                    ?: _uiState.value.distillingArticles.firstOrNull { it.id == articleId }
                _uiState.value = _uiState.value.copy(
                    pendingArticles = _uiState.value.pendingArticles.filter { it.id != articleId },
                    distillingArticles = if (moved != null) {
                        (_uiState.value.distillingArticles + moved).distinctBy { it.id }
                    } else {
                        _uiState.value.distillingArticles
                    },
                    failedArticles = _uiState.value.failedArticles.filter { it.id != articleId },
                )
                startPolling(articleId, taskId)
            } catch (e: Exception) {
                android.util.Log.w("DistillViewModel", "distill failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isDistilling = false,
                    error = friendlyError(e, fallback = "蒸馏失败，请稍后再试"),
                )
            }
        }
    }

    /**
     * 启动真轮询：每 [POLL_INTERVAL_MS] 查一次蒸馏状态，直到 READY / FAILED 或超时。
     * - READY：从待蒸馏列表移除 + toast "蒸馏完成，可收听"
     * - FAILED：保留文章 + 提示失败，允许用户在列表里重试
     */
    private fun startPolling(articleId: String, taskId: String) {
        pollJobs[articleId]?.cancel()

        // 超过并发上限就不起轮询器（见 distill() 里的说明）：服务端独立跑完，
        // 列表页下次刷新直接能看到结果。
        if (pollJobs.count { it.value.isActive } >= maxConcurrentPolls) {
            android.util.Log.i(
                "DistillViewModel",
                "跳过轮询 article=$articleId（已达并发上限 $maxConcurrentPolls）",
            )
            return
        }

        pollJobs[articleId] = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                inProgress = _uiState.value.inProgress + (articleId to DistillInProgress(articleId)),
                isDistilling = false,
            )
            var attempts = 0
            // 连续网络失败次数（2026-10-03）：单次失败不终止轮询，
            // 连续 MAX_CONSECUTIVE_POLL_FAILURES 次才放弃并给出可重试提示
            var consecutiveFailures = 0
            while (attempts < MAX_POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                attempts++
                try {
                    val status = repository.getDistillStatus(taskId)
                    // 查询成功即重置连续失败计数，避免抖动被误判成服务不可达
                    consecutiveFailures = 0
                    when (status) {
                        DistillStatus.READY -> {
                            // CP-DISTILL：READY → 从 distilling 桶移除
                            _uiState.value = _uiState.value.copy(
                                inProgress = _uiState.value.inProgress - articleId,
                                distillingArticles = _uiState.value.distillingArticles.filter { it.id != articleId },
                                pendingArticles = _uiState.value.pendingArticles.filter { it.id != articleId },
                                toast = "蒸馏完成，可收听",
                            )
                            pollJobs.remove(articleId)
                            return@launch
                        }
                        DistillStatus.FAILED -> {
                            // CP-DISTILL：FAILED → 从 distilling 桶移到 failed 桶
                            val moved = _uiState.value.distillingArticles
                                .firstOrNull { it.id == articleId }
                                ?: Article(id = articleId, url = "", title = "（已删除）")
                            _uiState.value = _uiState.value.copy(
                                inProgress = _uiState.value.inProgress - articleId,
                                distillingArticles = _uiState.value.distillingArticles.filter { it.id != articleId },
                                failedArticles = (_uiState.value.failedArticles + moved).distinctBy { it.id },
                                error = "蒸馏失败，请重试",
                            )
                            pollJobs.remove(articleId)
                            return@launch
                        }
                        else -> { /* distilling / pending：继续轮询 */ }
                    }
                } catch (e: Exception) {
                    // ⚠️ 2026-10-03 修两处：
                    // ① 原来一次异常就 `return@launch` 终止轮询。一次 5xx / 65s 超时
                    //    就让文章永远显示「处理中」，而背后没有任何任务在跑。
                    // ② 原来把 inProgress 摘掉了，却**没把文章移出 distillingArticles 桶**
                    //    （对比下面超时分支有移桶）—— 于是它既不在进行中、也不在失败里。
                    consecutiveFailures++
                    if (consecutiveFailures < MAX_CONSECUTIVE_POLL_FAILURES) {
                        // 还没到阈值：留在 distilling 桶继续下一轮
                        continue
                    }
                    val movedArticle = _uiState.value.distillingArticles
                        .firstOrNull { it.id == articleId }
                        ?: Article(id = articleId, url = "", title = "（已删除）")
                    _uiState.value = _uiState.value.copy(
                        inProgress = _uiState.value.inProgress - articleId,
                        distillingArticles = _uiState.value.distillingArticles.filter { it.id != articleId },
                        failedArticles = (_uiState.value.failedArticles + movedArticle).distinctBy { it.id },
                        error = friendlyError(
                            e,
                            fallback = "连续 $MAX_CONSECUTIVE_POLL_FAILURES 次无法查询蒸馏状态，请稍后重试",
                        ),
                    )
                    pollJobs.remove(articleId)
                    return@launch
                }
            }
            // 超过最大尝试次数仍未 ready → 超时（视为失败，从 distilling 桶移到 failed 桶）
            val movedArticle = _uiState.value.distillingArticles
                .firstOrNull { it.id == articleId }
                ?: Article(id = articleId, url = "", title = "（已删除）")
            _uiState.value = _uiState.value.copy(
                inProgress = _uiState.value.inProgress - articleId,
                distillingArticles = _uiState.value.distillingArticles.filter { it.id != articleId },
                failedArticles = (_uiState.value.failedArticles + movedArticle).distinctBy { it.id },
                error = "蒸馏超时（>90s），请稍后重试",
            )
            pollJobs.remove(articleId)
        }
    }

    /**
     * 用户中途返回 — 取消所有未完成轮询。
     */
    override fun onCleared() {
        pollJobs.values.forEach { it.cancel() }
        pollJobs.clear()
        super.onCleared()
    }

    fun consumeToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    /**
     * CP-DELETE：删除待蒸馏/失败的文章（硬删除，后端级联清理）。
     * - 若该文章正在轮询蒸馏 → 先取消（文章已删，继续查 task 只会报错）
     * - 成功 → pendingArticles 本地即时移除 + toast；失败 → error 走 Toast
     */
    private val _deletingId = MutableStateFlow<String?>(null)
    val deletingId: StateFlow<String?> = _deletingId.asStateFlow()

    fun deleteArticle(id: String) {
        if (_deletingId.value != null) return
        _deletingId.value = id
        pollJobs[id]?.cancel()
        pollJobs.remove(id)
        viewModelScope.launch {
            try {
                repository.deleteArticle(id)
                _uiState.value = _uiState.value.copy(
                    pendingArticles = _uiState.value.pendingArticles.filterNot { it.id == id },
                    distillingArticles = _uiState.value.distillingArticles.filterNot { it.id == id },
                    failedArticles = _uiState.value.failedArticles.filterNot { it.id == id },
                    inProgress = _uiState.value.inProgress - id,
                    toast = "已删除",
                )
            } catch (e: Exception) {
                android.util.Log.w("DistillViewModel", "deleteArticle failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    error = friendlyError(e, fallback = "删除失败"),
                )
            } finally {
                _deletingId.value = null
            }
        }
    }

    companion object {
        /**
         * 轮询间隔：15 秒（2026-10-03 从 3s 拉长）。
         *
         * 原来 3s × 30 次 = **90 秒窗口**，但实测单篇蒸馏要 12 分钟
         * （本机 TTS 约 745s/篇，生产最长音频 598s）—— 也就是说轮询**必然在
         * 蒸馏完成之前就放弃**，用户看到「蒸馏超时」，而服务端还在跑。
         * 这比「网络抖动导致提前放弃」更根本。
         *
         * 15s 对分钟级的操作完全够用：等待窗口本来就是分钟级，3s 的刷新密度
         * 对用户毫无感知价值，却把请求量放大了 5 倍（多篇并发时是 N 倍）。
         */
        const val POLL_INTERVAL_MS: Long = 15_000L

        /** 连续网络失败多少次才放弃轮询（2026-10-03，约 9 秒） */
        const val MAX_CONSECUTIVE_POLL_FAILURES: Int = 3

        /**
         * 最大轮询次数：60 次 × 15s ≈ 15 分钟。
         *
         * 原来 30 次 × 3s = 90 秒，远短于实测的 12 分钟蒸馏耗时，轮询必然提前
         * 放弃，用户看到「超时」而服务端还在跑。15 分钟覆盖实测耗时并留余量。
         */
        const val MAX_POLL_ATTEMPTS = 60
    }
}
