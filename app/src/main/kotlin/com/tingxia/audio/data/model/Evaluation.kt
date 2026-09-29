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

/**
 * GET §2.6 评分读回响应（`GET /api/v1/distill/{task_id}/evaluation`）。
 *
 * 为什么需要这个模型
 * ------------------
 * 评分一直是"只写不读"：POST 写进 `distillation_evaluations`，而这张表长期只有
 * `/api/v1/admin/evaluations` 一个 admin 读入口。于是提交完只能关弹窗，界面上
 * 没有任何"我评过这篇"的痕迹；重进文章不知道评没评过，接口又不幂等，再点一次
 * 就多写一行。真机 DB 上 `distillation_evaluations` 长期 0 条、界面也无从确认，
 * 这就是"提交评分没有闭环"。
 *
 * 语义：**没评过返回 200 + 全 null**（不是 404）—— 调用方要能区分
 * "这篇我还没评"（→ 弹评分卡）和"这篇不存在/不是我的"（→ 报错）。
 */
@Serializable
data class MyEvaluationResponse(
    /** evaluation 主键；未评过时为 null */
    val id: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("hook_score") val hookScore: Int? = null,
    @SerialName("section_score") val sectionScore: Int? = null,
    @SerialName("outro_score") val outroScore: Int? = null,
    @SerialName("rhythm_score") val rhythmScore: Int? = null,
    @SerialName("overall_score") val overallScore: Int? = null,
    val comment: String? = null,
    @SerialName("skip_reason") val skipReason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    /** 是否已评分（后端返回 null id 即"没评过"）。 */
    val isRated: Boolean get() = id != null
}