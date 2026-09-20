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
