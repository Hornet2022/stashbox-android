package com.tingxia.audio.data.repository

import com.tingxia.audio.data.model.EvaluationRequest
import com.tingxia.audio.data.model.EvaluationResponse
import com.tingxia.audio.data.remote.DistillationApi
import javax.inject.Inject

/**
 * §2.6 4 维听感评分仓库（CP3.7.0 评分 UI 的写入口）。
 *
 * 调用前需保证 `taskId` 已就绪（来自 §1.3 状态响应）。
 *
 * **联动语义（客户端无需处理，但应理解响应字段以便运营叙事）**：
 * - `inFewShotPool=true` → overall≥4 且 hook 文本有效时自动入池
 * - `patternUpdated=true` → 听感画像已增量更新（冷启动 <5 篇时 false 属正常）
 * - 联动失败不会让请求失败（响应仍 200，标志位 false），**不需重试补偿**
 *
 * **不幂等**：每次调用写一行 evaluation 记录，UI 必须防连点（按钮 disable + 提交中 loading）。
 */
class EvaluationRepository @Inject constructor(private val api: DistillationApi) {

    suspend fun submit(
        taskId: String,
        hookScore: Int? = null,
        sectionScore: Int? = null,
        outroScore: Int? = null,
        rhythmScore: Int? = null,
        overallScore: Int,
        comment: String? = null,
        skipReason: String? = null,
    ): EvaluationResponse = api.submitEvaluation(
        taskId,
        EvaluationRequest(
            hookScore = hookScore,
            sectionScore = sectionScore,
            outroScore = outroScore,
            rhythmScore = rhythmScore,
            overallScore = overallScore,
            comment = comment,
            skipReason = skipReason,
        ),
    )
}