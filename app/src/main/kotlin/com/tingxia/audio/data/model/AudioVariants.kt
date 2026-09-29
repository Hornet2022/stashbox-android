package com.tingxia.audio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * §3.1 多码率档位。
 *
 * - 128k 是主档（蒸馏产物，恒指向 [Article.audioUrl]）
 * - 96k / 64k 首次请求时服务端按需 ffmpeg 转码生成
 * - `available=false` 客户端回退上一档，全 false 才用主档 URL
 */
@Serializable
data class AudioVariant(
    /** 码率 kbps ∈ {128, 96, 64} */
    val bitrate: Int,
    /** 该档是否可用（转码失败则为 false） */
    val available: Boolean,
    /** 音频 URL（available=true 才有；主档 URL 带签名参数） */
    val url: String? = null,
    @SerialName("file_size_bytes") val fileSizeBytes: Long? = null,
    /** 是否主档（恒指向蒸馏产物 128k） */
    @SerialName("is_main") val isMain: Boolean = false,
)

/**
 * §3.1 多码率协商响应。
 */
@Serializable
data class AudioVariantsResponse(
    @SerialName("task_id") val taskId: String,
    @SerialName("article_id") val articleId: String,
    val variants: List<AudioVariant> = emptyList(),
) {
    /** 选档逻辑：网络感知 + 用户偏好 + available 链。详见 [com.tingxia.audio.audio.VariantSelectionUseCase]。 */
    fun selectByBitrate(bitrate: Int): AudioVariant? =
        variants.firstOrNull { it.bitrate == bitrate && it.available }
}

/**
 * §3.2 预热响应。
 *
 * `generated=false` 表示转码失败/无主音频——**仍是 200**，客户端按"不可预热"处理走在线播放。
 */
@Serializable
data class VariantWarmResponse(
    @SerialName("task_id") val taskId: String,
    val bitrate: Int,
    val generated: Boolean,
    @SerialName("oss_key") val ossKey: String? = null,
    @SerialName("file_size_bytes") val fileSizeBytes: Long? = null,
)