package com.tingxia.audio.data

import com.tingxia.audio.data.model.AudioVariantsResponse
import com.tingxia.audio.data.model.DistillStartRequest
import com.tingxia.audio.data.model.DistillStartResponse
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
 * - [startDistill] 返成功，并记进 [lastDistillStart] —— CP-TTS-VOICE 新增，
 *   同样必须给默认实现，否则整个 `compileDebugUnitTestKotlin` 直接失败
 *   （Kotlin 不会像 Java 那样只对用到的方法报错）
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

    /** CP-TTS-VOICE：最近一次 [startDistill] 的请求体（测试断言用）。 */
    var lastDistillStart: DistillStartRequest? = null
        private set

    /** CP-TTS-VOICE：[startDistill] 调用次数。 */
    var startCount: Int = 0
        private set

    /** [startDistill] 抛错时置 true（模拟提交重生成失败）。 */
    var failDistillStart: Boolean = false

    /** [startDistill] 返回的 task_id；默认由 article_id 派生，便于断言幂等。 */
    var distillStartTaskId: ((DistillStartRequest) -> String)? = null

    override suspend fun getVariants(taskId: String): AudioVariantsResponse =
        throw UnsupportedOperationException("not needed in this test")

    /**
     * CP-TTS-VOICE：返回体可被测试覆写；不给就用 [distillStartTaskId]（缺省按
     * article_id 派生）。`voice_name` 默认给个可辨识的假值，好让断言
     * 「确认弹窗里带上了音色名」这类用例不用各自造 fixture。
     */
    override suspend fun startDistill(body: DistillStartRequest): DistillStartResponse {
        if (failDistillStart) throw RuntimeException("simulated distill start failure")
        lastDistillStart = body
        startCount += 1
        val taskId = distillStartTaskId?.invoke(body) ?: "dst_fake_${body.articleId}"
        return DistillStartResponse(
            taskId = taskId,
            articleId = body.articleId,
            status = "queued",
            jobId = "job_fake_$startCount",
            voiceId = "vce_fake_1",
            voiceName = "测试音色",
        )
    }

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
