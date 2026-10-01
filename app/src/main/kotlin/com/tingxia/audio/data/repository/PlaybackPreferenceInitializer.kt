package com.tingxia.audio.data.repository

import com.tingxia.audio.audio.PlayerController
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放偏好的一次性初始化（2026-10-02）。
 *
 * ## 为什么需要它
 *
 * 语速/音色偏好的加载原本挂在 `TtsPreferenceViewModel.init { load() }` 上，
 * 而那个 ViewModel 只在**详情页 / 设置页 / 全屏播放器**里 `hiltViewModel()`
 * 才会被构造。后果是：App 启动后如果用户从别的入口播放，倍速还是 1.0x，
 * 要等他偶然打开设置页才生效 —— 「配了但不像配了」。
 *
 * 偏好在语义上属于**播放器**，不属于某个界面。加载一次、落到 PlayerController，
 * 之后无论从哪个入口播都一致。
 */
@Singleton
class PlaybackPreferenceInitializer @Inject constructor(
    private val ttsRepository: TtsRepository,
    private val playerController: PlayerController,
) {
    @Volatile
    private var started = false

    /**
     * 幂等。失败不抛 —— 拿不到偏好就用 1.0x 兜底，
     * 绝不能让 App 卡在启动态。
     */
    suspend fun ensureLoaded() {
        if (started) return
        started = true
        try {
            val pref = ttsRepository.getPreference()
            playerController.applySpeedPreference(
                preferred = pref.speed,
                options = pref.availableSpeeds
                    .ifEmpty { PlayerController.DEFAULT_AVAILABLE_SPEEDS },
            )
        } catch (e: Exception) {
            // 网络不通也要能播：保持默认档位
            started = false // 允许下次重试
        }
    }
}
