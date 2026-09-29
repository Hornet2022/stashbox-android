package com.tingxia.audio.data

import com.tingxia.audio.data.model.AudioVariantsResponse
import com.tingxia.audio.data.model.EvaluationRequest
import com.tingxia.audio.data.model.EvaluationResponse
import com.tingxia.audio.data.model.MyEvaluationResponse
import com.tingxia.audio.data.model.VariantWarmResponse
import com.tingxia.audio.data.remote.DistillationApi

/**
 * 测试用 DistillationApi 替身。
 *
 * 跟 [TestArticleApi] 同一个理由：接口加了方法后，所有依赖它的测试都要跟着改。
 * 这里给一份完整默认实现，测试用例只需覆写关心的方法。
 *
 * 默认行为：
 * - [getMyEvaluation] 返「没评过」（全 null）—— 这是多数测试想要的状态
 * - [submitEvaluation] 返成功，并把该次评分记进 [lastSubmitted]，
 *   方便断言"确实提交了"和预填逻辑
 */
open class FakeDistillationApi : DistillationApi {

    /** 最近一次提交的请求体（测试断言用）。 */
    var lastSubmitted: EvaluationRequest? = null
        private set

    /** 提交次数。 */
    var submitCount: Int = 0
        private set

    /** [getMyEvaluation] 的返回值；默认全 null = 没评过。 */
    var myRating: MyEvaluationResponse = MyEvaluationResponse()

    /** [getMyEvaluation] 抛错时置 true（模拟读回失败）。 */
    var failMyRating: Boolean = false

    /** [submitEvaluation] 抛错时置 true。 */
    var failSubmit: Boolean = false

    override suspend fun getVariants(taskId: String): AudioVariantsResponse =
        throw UnsupportedOperationException("not needed in this test")

    override suspend fun warmVariant(
        taskId: String,
        bitrate: Int,
    ): VariantWarmResponse = throw UnsupportedOperationException("not needed in this test")

    override suspend fun submitEvaluation(
        taskId: String,
        body: EvaluationRequest,
    ): EvaluationResponse {
        if (failSubmit) throw RuntimeException("simulated submit failure")
        lastSubmitted = body
        submitCount += 1
        return EvaluationResponse(
            id = "eval_fake_1",
            taskId = taskId,
            overallScore = body.overallScore,
            inFewShotPool = body.overallScore >= 4,
            patternUpdated = true,
        )
    }

    override suspend fun getMyEvaluation(taskId: String): MyEvaluationResponse {
        if (failMyRating) throw RuntimeException("simulated read failure")
        return myRating
    }
}
