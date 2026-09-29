package com.tingxia.audio.ui.evaluation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.model.EvaluationResponse
import com.tingxia.audio.data.repository.EvaluationRepository
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * §2.6 4 维听感评分 ViewModel（CP3.7.0 评分 UI）。
 *
 * 数据流：
 * 1. UI 调 [initWithTask](taskId) 注入 task_id（来自 §1.3 状态响应）
 * 2. 4 维分数 + 总评 + 跳过原因 + 评论均存在 [UiState]
 * 3. 用户点提交 → [submit]，成功 = [SubmitState.Success]（含 in_few_shot_pool/pattern_updated）
 *
 * **关键约束**：
 * - `overallScore` 必填且 1-5（其他维度可空）
 * - `skipReason` ≤32 字符
 * - 接口**不幂等**，每次提交写一条记录 → UI 提交中按钮 disable
 */
@HiltViewModel
class EvaluationViewModel @Inject constructor(
    private val repository: EvaluationRepository,
    private val ratingPolicy: RatingPolicy,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _submitState = MutableStateFlow<SubmitState>(SubmitState.Idle)
    val submitState: StateFlow<SubmitState> = _submitState.asStateFlow()

    fun initWithTask(taskId: String) {
        _uiState.update { it.copy(taskId = taskId) }
    }

    fun setHookScore(score: Int?) = _uiState.update { it.copy(hookScore = score) }
    fun setSectionScore(score: Int?) = _uiState.update { it.copy(sectionScore = score) }
    fun setOutroScore(score: Int?) = _uiState.update { it.copy(outroScore = score) }
    fun setRhythmScore(score: Int?) = _uiState.update { it.copy(rhythmScore = score) }
    fun setOverallScore(score: Int) = _uiState.update { it.copy(overallScore = score) }
    fun setComment(text: String) = _uiState.update { it.copy(comment = text.take(MAX_COMMENT_LEN)) }
    fun setSkipReason(reason: String?) =
        _uiState.update { it.copy(skipReason = reason?.take(MAX_SKIP_REASON_LEN)) }

    /** 校验：overallScore 1-5，其他维度可空（null 表示跳过），comment ≤ 500。 */
    fun isValid(): Boolean {
        val s = _uiState.value
        return s.overallScore in 1..5 &&
            s.hookScore?.let { it in 1..5 } ?: true &&
            s.sectionScore?.let { it in 1..5 } ?: true &&
            s.outroScore?.let { it in 1..5 } ?: true &&
            s.rhythmScore?.let { it in 1..5 } ?: true &&
            (s.skipReason == null || s.skipReason.length <= MAX_SKIP_REASON_LEN)
    }

    fun submit() {
        val s = _uiState.value
        val taskId = s.taskId
        if (taskId == null) {
            _submitState.value = SubmitState.Error("缺少 taskId，无法评分")
            return
        }
        if (!isValid()) {
            _submitState.value = SubmitState.Error("总评分必须是 1-5 星")
            return
        }
        if (_submitState.value is SubmitState.Loading) return  // 防连点

        _submitState.value = SubmitState.Loading
        viewModelScope.launch {
            runCatching {
                repository.submit(
                    taskId = taskId,
                    hookScore = s.hookScore,
                    sectionScore = s.sectionScore,
                    outroScore = s.outroScore,
                    rhythmScore = s.rhythmScore,
                    overallScore = s.overallScore,
                    comment = s.comment.takeIf { it.isNotBlank() },
                    skipReason = s.skipReason,
                )
            }
                .onSuccess { resp ->
                    ratingPolicy.recordRated()
                    _submitState.value = SubmitState.Success(resp)
                }
                .onFailure { e ->
                    _submitState.value = SubmitState.Error(friendlyError(e, fallback = "提交失败"))
                }
        }
    }

    /** 关闭弹窗前手动调：记录已弹窗一次（用于 §2.5 频次策略） */
    fun markPromptShown() {
        viewModelScope.launch { ratingPolicy.recordPromptShown() }
    }

    fun reset() {
        _uiState.value = UiState()
        _submitState.value = SubmitState.Idle
    }

    data class UiState(
        val taskId: String? = null,
        val hookScore: Int? = null,
        val sectionScore: Int? = null,
        val outroScore: Int? = null,
        val rhythmScore: Int? = null,
        val overallScore: Int = 0,
        val comment: String = "",
        val skipReason: String? = null,
    )

    sealed class SubmitState {
        data object Idle : SubmitState()
        data object Loading : SubmitState()
        data class Success(val response: EvaluationResponse) : SubmitState()
        data class Error(val message: String) : SubmitState()
    }

    companion object {
        const val MAX_COMMENT_LEN = 500
        const val MAX_SKIP_REASON_LEN = 32

        /** 跳过原因推荐枚举（接口文档 §2.3 推荐值，§2.6 skip_reason 也复用） */
        val SKIP_REASONS = listOf(
            "too_long" to "太长",
            "too_short" to "太短",
            "boring" to "无聊",
            "low_quality" to "质量差",
            "not_interested" to "不感兴趣",
            "other" to "其他",
        )
    }
}