package com.tingxia.audio.ui.distill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.repository.ArticleRepository
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
 * - 成功 → 该行从列表里移除 + Toast 提示
 *
 * CP11.0.5 P3.2 升级:用户点 "立即蒸馏" 后,UI 显示 30 秒真倒计时进度条 + 剩余秒数
 * (期间按钮 disable),倒计时结束 + 文章状态更新才真正消失。
 *
 * 设计权衡: 不等后端 PUSH,前端用 30s 估算(后端 ai-service 实测 ~10-30s 完成)。
 */
@HiltViewModel
class DistillViewModel @Inject constructor(
    private val repository: ArticleRepository,
) : ViewModel() {

    /** P3.2: 蒸馏倒计时默认秒数(后端平均完成时间)。 */
    companion object {
        const val COUNTDOWN_SECONDS = 30
    }

    /**
     * 某篇文章正在倒计时中的状态(per-article key,避免按钮被全局 disable)。
     * 文章被点 → 进入 in-progress(remaining=30, startedAt=now)
     * 倒计时到 0 → 状态置 completed,UI 自动清掉
     * 用户中途返回 → onCleared 取消 job
     */
    data class DistillInProgress(
        val articleId: String,
        val remaining: Int,           // 剩余秒数
        val total: Int = COUNTDOWN_SECONDS,
    )

    data class UiState(
        val pendingArticles: List<Article> = emptyList(),
        val isLoading: Boolean = false,
        val isDistilling: Boolean = false,
        val error: String? = null,
        val toast: String? = null,
        // CP11.0.5 P3.2: 倒计时中的文章(可同时多个)
        val inProgress: Map<String, DistillInProgress> = emptyMap(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // 防止多个 distill job 撞同一文章
    private val countdownJobs: MutableMap<String, Job> = mutableMapOf()

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
        // 已在倒计时中,忽略重复点击
        if (countdownJobs[articleId]?.isActive == true) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDistilling = true, error = null)
            try {
                val resp = repository.distillArticle(articleId)
                android.util.Log.i(
                    "DistillViewModel",
                    "distill ok: articleId=$articleId status=${resp.status} taskId=${resp.taskId}",
                )

                // CP11.0.5 P3.2: 启动 30 秒倒计时
                startCountdown(articleId)
            } catch (e: Exception) {
                android.util.Log.w("DistillViewModel", "distill failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isDistilling = false,
                    error = e.message ?: "蒸馏失败",
                )
            }
        }
    }

    /**
     * 启动 30 秒倒计时(per-article)。
     * 每秒 remaining--,到 0 后从 pendingArticles 移除 + 弹 toast。
     */
    private fun startCountdown(articleId: String) {
        countdownJobs[articleId]?.cancel()
        countdownJobs[articleId] = viewModelScope.launch {
            // 初始状态
            _uiState.value = _uiState.value.copy(
                inProgress = _uiState.value.inProgress + (articleId to DistillInProgress(articleId, COUNTDOWN_SECONDS)),
                isDistilling = false,    // 还原按钮(此时进入"倒计时中"而非"派发中")
            )
            for (i in COUNTDOWN_SECONDS - 1 downTo 0) {
                delay(1000)
                _uiState.value = _uiState.value.copy(
                    inProgress = _uiState.value.inProgress + (articleId to DistillInProgress(articleId, i)),
                )
                if (i == 0) break
            }
            // 倒计时结束:从列表移除 + 提示
            _uiState.value = _uiState.value.copy(
                inProgress = _uiState.value.inProgress - articleId,
                pendingArticles = _uiState.value.pendingArticles.filter { it.id != articleId },
                toast = "蒸馏完成",
            )
            countdownJobs.remove(articleId)
        }
    }

    /**
     * 用户中途返回 — 取消所有未完成倒计时。
     * (避免后台跑一堆 countdown job 浪费电)
     */
    override fun onCleared() {
        countdownJobs.values.forEach { it.cancel() }
        countdownJobs.clear()
        super.onCleared()
    }

    fun consumeToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }
}