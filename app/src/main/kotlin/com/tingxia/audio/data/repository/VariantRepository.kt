package com.tingxia.audio.data.repository

import com.tingxia.audio.data.model.AudioVariant
import com.tingxia.audio.data.model.AudioVariantsResponse
import com.tingxia.audio.data.model.VariantWarmResponse
import com.tingxia.audio.data.remote.DistillationApi
import javax.inject.Inject

/**
 * §3 多码率音频变体仓库（CP7.3.0 / CP7.4.0 客户端半边）。
 *
 * - §3.1 协商：`getVariants(taskId)` 返回 128/96/64 三档可用性
 * - §3.2 预热：`warmVariant(taskId, bitrate)` 触发服务端按需 ffmpeg 转码
 *
 * **注意**：§3.1 首次调用可能慢（转码 5 分钟音频 ≈ 秒级，**超时上限 60s**），
 * 客户端不能阻塞。变体选择策略见 [com.tingxia.audio.audio.VariantSelectionUseCase]。
 */
class VariantRepository @Inject constructor(private val api: DistillationApi) {

    /** §3.1 协商码率。失败抛异常（连接/超时），调用方决定是否降级主档。 */
    suspend fun getVariants(taskId: String): AudioVariantsResponse =
        api.getVariants(taskId)

    /**
     * §3.2 预热指定码率（仅 96/64 需要；128 是主档）。
     *
     * 返回 200 即便 `generated=false`（转码失败）。`bitrate must be one of [96, 64]` 越界 → 400。
     */
    suspend fun warmVariant(taskId: String, bitrate: Int): VariantWarmResponse {
        require(bitrate == 96 || bitrate == 64) { "bitrate must be 96 or 64 (main 128 doesn't need warm)" }
        return api.warmVariant(taskId, bitrate)
    }

    /**
     * 链式 fallback：依次尝试 128 → 96 → 64，返回第一个 available 的档。
     * 全 false 时返回主档 128（即便 available=false，作为最后兜底）。
     */
    fun pickAvailable(variants: List<AudioVariant>, preferred: Int): AudioVariant? {
        val sorted = listOf(128, 96, 64)
        // 先找 preferred 档
        variants.firstOrNull { it.bitrate == preferred && it.available }?.let { return it }
        // 否则按 fallback 顺序找第一个 available
        for (b in sorted) {
            variants.firstOrNull { it.bitrate == b && it.available }?.let { return it }
        }
        // 全 false 时兜底 128
        return variants.firstOrNull { it.bitrate == 128 }
    }
}