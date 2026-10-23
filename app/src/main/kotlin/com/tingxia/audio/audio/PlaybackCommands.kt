package com.tingxia.audio.audio

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * 通知栏 / 系统媒体面板的**自定义命令**（2026-10-23 新增）。
 *
 * ## 为什么需要自定义命令
 *
 * Media3 的通知按钮分两类：
 *
 * - **player 命令**（`COMMAND_PLAY_PAUSE` 等）：由播放器自己实现，
 *   Media3 直接转发给 ExoPlayer；
 * - **自定义命令**（`SessionCommand`）：App 自己实现的行为，
 *   经 `MediaSession.Callback.onCustomCommand` 派发。
 *
 * 「上一首 / 下一首 / 快退 / 快进 / 睡眠定时器」这几件事**不属于播放器的
 * 原生能力**（队列在 App 层、���时器是 App 的策略），所以走自定义命令。
 *
 * ⚠️ Media3 1.4.1 的 `DefaultMediaNotificationProvider` 只把
 * **custom 类型**（`commandCode == COMMAND_CODE_CUSTOM`）的按钮放进通知，
 * 且 `onConnect` 暴露的 `availableSessionCommands` 之外的自定义命令
 * 还要在 `MediaSession.setCustomLayout` 里登记 —— 三者缺一，按钮就不出现。
 *
 * ## 名字为什么带包名前缀
 *
 * 自定义命令的 action 名是**全局字符串**，撞名会让别的 App 的命令被本 App
 * 误收（反过来也一样）。约定用包名前缀。
 */
object PlaybackCommands {

    /** 快退 10 秒（步长见 PlayerModule.SEEK_BACK_MS） */
    const val ACTION_SEEK_BACK = "com.tingxia.audio.SEEK_BACK"

    /** 快进 30 秒（步长见 PlayerModule.SEEK_FORWARD_MS） */
    const val ACTION_SEEK_FORWARD = "com.tingxia.audio.SEEK_FORWARD"

    /** 上一首（走 App 层的队列，见 PlayerController.playPrevious） */
    const val ACTION_SKIP_PREVIOUS = "com.tingxia.audio.SKIP_PREVIOUS"

    /** 下一首（走 App 层的队列，见 PlayerController.playNext） */
    const val ACTION_SKIP_NEXT = "com.tingxia.audio.SKIP_NEXT"

    /** 睡眠定时器：到点自动停 */
    const val ACTION_SLEEP_TIMER = "com.tingxia.audio.SLEEP_TIMER"

    /** 睡眠定时器的分钟数（int extra） */
    const val EXTRA_MINUTES = "com.tingxia.audio.MINUTES"

    /** 构造一个自定义命令（extras 可空）。 */
    fun of(action: String, extras: Bundle? = null): SessionCommand =
        SessionCommand(action, extras ?: Bundle())
}
