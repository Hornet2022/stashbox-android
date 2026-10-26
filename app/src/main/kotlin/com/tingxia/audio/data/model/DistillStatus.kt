package com.tingxia.audio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 蒸馏任务状态。
 * - PENDING：已提交，等待处理
 * - DISTILLING：蒸馏中
 * - READY：完成，可播放
 * - FAILED：失败
 * - LISTENED：已听过（列表页状态徽章用）
 */
@Serializable
enum class DistillStatus {
    @SerialName("pending")
    PENDING,
    @SerialName("distilling")
    DISTILLING,
    @SerialName("ready")
    READY,
    @SerialName("failed")
    FAILED,
    @SerialName("listened")
    LISTENED,
}

/**
 * 该状态是否已有可播放音频。
 *
 * READY 与 LISTENED 都代表蒸馏产出已就绪：LISTENED 只是「用户已听完」的附加标记，
 * 音频文件仍存在、仍可重播（详情页重新打开会再次自动播放）。所有「有音频才有意义」的
 * UI 闸门（播放条、下载、评分等）都必须用这个判断，而不是 `== READY` ——
 * 否则已听过的文章会「音频在播却没有播放条 / 下载按钮」（回归：ArticleDetailScreen 底部条）。
 */
val DistillStatus.hasAudio: Boolean
    get() = this == DistillStatus.READY || this == DistillStatus.LISTENED

/**
 * CP-TTS-VOICE: `POST /api/v1/distill/start` 的请求体。
 *
 * 只传 `article_id` 就够 —— `url` / `title` 后端一律取库里的值。
 * 曾经 `url` 是必填的（尽管后端根本不用它），App 调「重新生成」时被迫编一个
 * url 出来，反而容易传错、以为能覆盖实际内容。
 */
@Serializable
data class DistillStartRequest(
    @SerialName("article_id") val articleId: String,
)

/**
 * CP-TTS-VOICE: `POST /api/v1/distill/start` 的响应。
 *
 * [voiceName] 是「这次会用哪个音色」—— 拿它在确认弹窗里告诉用户，
 * 而不是让用户点了之后不知道音频会变成谁念的。
 * 音色解析发生在服务端（user → default → global_config 三级回退），
 * 所以 App 不用自己猜。
 */
@Serializable
data class DistillStartResponse(
    @SerialName("task_id") val taskId: String,
    @SerialName("article_id") val articleId: String = "",
    val status: String = "",
    @SerialName("job_id") val jobId: String = "",
    @SerialName("voice_id") val voiceId: String? = null,
    @SerialName("voice_name") val voiceName: String? = null,
)
