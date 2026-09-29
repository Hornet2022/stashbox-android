package com.tingxia.audio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * §2.6 4 维听感评分请求体。
 *
 * 各维度分数 ∈ [1,5] 整数，可 null（用户跳过该维）；`overall` 必填。
 * `skipReason` ≤32 字符（评分弹窗内的跳过原因，CP3.7.0 UI 用）。
 *
 * ⚠️ **不幂等**：每次调用写一行 evaluation 记录，UI 防连点。
 */
@Serializable
data class EvaluationRequest(
    /** 开场吸引力 1-5（可 null） */
    @SerialName("hook_score") val hookScore: Int? = null,
    /** 章节节奏 1-5（可 null） */
    @SerialName("section_score") val sectionScore: Int? = null,
    /** 结尾收束 1-5（可 null） */
    @SerialName("outro_score") val outroScore: Int? = null,
    /** 语速节拍 1-5（可 null） */
    @SerialName("rhythm_score") val rhythmScore: Int? = null,
    /** 总评 1-5（必填） */
    @SerialName("overall_score") val overallScore: Int,
    /** 自由文本评论（可选） */
    val comment: String? = null,
    /** 跳过原因（≤32 字符） */
    @SerialName("skip_reason") val skipReason: String? = null,
)

/**
 * §2.6 评分提交响应。
 *
 * 联动语义（**客户端无需处理但要理解**）：
 * - `inFewShotPool=true` → overall≥4 且 hook 文本有效时自动入 few-shot 池，
 *   可直接用于「我的评分被用上了吗」类运营叙事；
 * - `patternUpdated=true` → 听感画像已增量更新（冷启动 <5 篇时 false 属正常）。
 *
 * 联动失败不会让请求失败（响应仍 200，标志位 false），**客户端不需要重试补偿逻辑**。
 */
@Serializable
data class EvaluationResponse(
    val id: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("overall_score") val overallScore: Int,
    @SerialName("in_few_shot_pool") val inFewShotPool: Boolean,
    @SerialName("pattern_updated") val patternUpdated: Boolean,
)