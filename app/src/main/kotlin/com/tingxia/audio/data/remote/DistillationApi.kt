package com.tingxia.audio.data.remote

import com.tingxia.audio.data.model.AudioVariantsResponse
import com.tingxia.audio.data.model.DistillStartRequest
import com.tingxia.audio.data.model.DistillStartResponse
import com.tingxia.audio.data.model.EvaluationRequest
import com.tingxia.audio.data.model.EvaluationResponse
import com.tingxia.audio.data.model.MyEvaluationResponse
import com.tingxia.audio.data.model.VariantWarmResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * 蒸馏任务级接口（与 [ArticleApi] 分离）。
 *
 * 路径前缀 `/api/v1/distill/{task_id}` 下的所有端点（接口文档 v1.2 §1.3 / §2.6 / §3.1 / §3.2）：
 * - GET  `/distill/{task_id}`                       老路径（保留兼容，新代码走 §1.3 文章级 status）
 * - GET  `/distill/{task_id}/variants`              §3.1 多码率协商
 * - POST `/distill/{task_id}/variants/{bitrate}/warm` §3.2 预热转码
 * - POST `/distill/{task_id}/evaluation`            §2.6 4 维听感评分
 * - GET  `/distill/{task_id}/evaluation`            §2.6 读回自己的最新评分
 *
 * task_id 形如 `dst_xxx`，来源：[com.tingxia.audio.data.model.Article.taskId]
 * 或 [com.tingxia.audio.data.model.DistillStatusResponse.task_id]（§1.3 状态响应）。
 */
interface DistillationApi {

    /** §3.1 查询可用码率（128/96/64）。首次可能触发服务端按需转码（≥ 65s 超时）。 */
    @GET("api/v1/distill/{task_id}/variants")
    suspend fun getVariants(@Path("task_id") taskId: String): AudioVariantsResponse

    /**
     * CP-TTS-VOICE: 触发/重新触发蒸馏 —— 同时也是「用当前音色重新生成」的入口。
     *
     * 刻意**不另开** `/re-distill` 端点：本项目在 CP-DISTILL-START-HARDEN 第 2 条上
     * 吃过亏（`/articles/{id}/distill` 扣配额而 `/distill/start` 不扣，同一件事两种
     * 计费口径）。再开一个入口只会重演 —— 要么不扣费造成不一致，要么重复扣费。
     *
     * `url` **不用传**（后端一律取库里的值，传什么蒸馏什么等于允许把 A 的产物写到 B）。
     *
     * @return 响应的 `voice_name` 是「这次会用哪个音色」，用来在确认弹窗里告诉用户
     */
    @POST("api/v1/distill/start")
    suspend fun startDistill(@Body body: DistillStartRequest): DistillStartResponse

    /**
     * §3.2 预热指定码率。
     *
     * @param bitrate ∈ {96, 64} —— 128 为主档不需要 warm
     * @return 200 即便 generated=false（转码失败也算成功，按"不可预热"处理走在线播放）
     */
    @POST("api/v1/distill/{task_id}/variants/{bitrate}/warm")
    suspend fun warmVariant(
        @Path("task_id") taskId: String,
        @Path("bitrate") bitrate: Int,
    ): VariantWarmResponse

    /**
     * §2.6 4 维听感评分（CP3.7.0 评分 UI 入口）。
     *
     * 校验：
     * - 各维度分数 1-5 整数，可 null（用户跳过该维）
     * - overall_score 必填 1-5
     * - skip_reason ≤32 字符（评分弹窗内跳过某维/整篇的结构化原因）
     *
     * 不幂等（每次调用写一行），UI 防连点。
     */
    @POST("api/v1/distill/{task_id}/evaluation")
    suspend fun submitEvaluation(
        @Path("task_id") taskId: String,
        @Body body: EvaluationRequest,
    ): EvaluationResponse

    /**
     * §2.6 评分读回：当前用户对这篇的最新一条听感评分（评分闭环读侧）。
     *
     * 没评过时后端返回 **200 + 全 null 字段**（不是 404）——
     * 404 在这个位置语义有歧义（"没评过" vs "这篇不存在/不是你的"），
     * 两种情况客户端要做的决策完全不同。
     */
    @GET("api/v1/distill/{task_id}/evaluation")
    suspend fun getMyEvaluation(
        @Path("task_id") taskId: String,
    ): MyEvaluationResponse
}