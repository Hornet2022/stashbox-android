package com.tingxia.audio.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.tingxia.audio.data.model.AudioVariant
import com.tingxia.audio.data.model.AudioVariantsResponse
import com.tingxia.audio.data.repository.VariantRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §3 多码率选档 use case（CP7.4.0 客户端半边）。
 *
 * 选档策略（接口文档 v1.2 §3.1 建议）：
 * - Wi-Fi 在线播放 → 128k（主档，蒸馏产物）
 * - 蜂窝网络在线播放 → 96k → 回退 128k
 * - 通勤预加载（离线缓存） → 64k → 回退 96k
 * - 省流量开关打开 → 64k
 *
 * 整体走 [pickBitrate]（网络 + 用户偏好），再经 [VariantRepository.pickAvailable] 兜底链。
 */
@Singleton
class VariantSelectionUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: VariantRepository,
) {

    enum class Scenario { WIFI_ONLINE, ETHERNET, CELLULAR_ONLINE, OFFLINE_PRELOAD, DATA_SAVER }

    /**
     * 检测当前网络场景。
     * - Wi-Fi → WIFI_ONLINE
     * - 蜂窝 → CELLULAR_ONLINE
     * - 未连接 → OFFLINE_PRELOAD（仅本地可播）
     */
    fun detectScenario(dataSaverEnabled: Boolean = false): Scenario {
        if (dataSaverEnabled) return Scenario.DATA_SAVER
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Scenario.OFFLINE_PRELOAD
        val network = cm.activeNetwork ?: return Scenario.OFFLINE_PRELOAD
        val caps = cm.getNetworkCapabilities(network) ?: return Scenario.OFFLINE_PRELOAD
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            Scenario.WIFI_ONLINE
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            Scenario.CELLULAR_ONLINE
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            Scenario.WIFI_ONLINE
        } else {
            Scenario.OFFLINE_PRELOAD
        }
    }

    /** 场景 → 期望码率（kbps） */
    fun preferredBitrate(scenario: Scenario): Int = when (scenario) {
        Scenario.WIFI_ONLINE, Scenario.ETHERNET -> 128
        Scenario.CELLULAR_ONLINE -> 96
        Scenario.OFFLINE_PRELOAD -> 64
        Scenario.DATA_SAVER -> 64
    }

    /**
     * 协商码率并选档：先 [VariantRepository.getVariants]，再按场景选 available 档。
     * 全 false 时回退主档 128（即便 not available，作为最后兜底）。
     *
     * @throws Exception 网络/服务端失败时抛出，由调用方决定是否降级主档
     */
    suspend fun pick(taskId: String, scenario: Scenario): AudioVariant {
        val resp: AudioVariantsResponse = repository.getVariants(taskId)
        val preferred = preferredBitrate(scenario)
        return repository.pickAvailable(resp.variants, preferred)
            ?: throw IllegalStateException("no variants returned for taskId=$taskId")
    }

    /** 仅按偏好档返回 URL，不查 variants（用于已经协商过的内存缓存） */
    fun pickFromCache(variants: List<AudioVariant>, scenario: Scenario): AudioVariant? {
        val preferred = preferredBitrate(scenario)
        return repository.pickAvailable(variants, preferred)
    }

    /** 列出所有可下载档（用于 UI 提示用户当前可预热的档位） */
    fun availableForDownload(variants: List<AudioVariant>): List<AudioVariant> =
        variants.filter { it.available && (it.bitrate == 96 || it.bitrate == 64) }
}