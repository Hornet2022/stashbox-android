// Media3 把大量播放/缓存 API 标为 @UnstableApi。
// 这些文件直接使用 ExoPlayer / SimpleCache / DownloadManager 等 unstable 声明，
// 必须显式 opt-in，否则 :app:lintDebug 报 UnsafeOptInUsageError 直接让 CI 失败。
//
// 注意用的是 **androidx.annotation.OptIn** 而不是 kotlin.OptIn：Media3 的
// UnstableApi 标的是 androidx 的 @RequiresOptIn，lint 认的是前者；写 Kotlin 的
// @OptIn 只会让 lint 在这行本身上再报一次（实测错误数 16 → 22）。
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
package com.tingxia.audio.audio.di

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.tingxia.audio.audio.OfflineDownloadManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 提供**共享** ExoPlayer 单例（CP4.4 修复双 ExoPlayer 架构断裂）。
 *
 * [PlayerController] 与 [com.tingxia.audio.audio.AudioPlayerService] 共用同一实例，
 * 后者将其绑定到 MediaSession，使锁屏 / 通知 / 后台统一控制真实出声的播放器。
 *
 * ## 这里同时是「播放器的平台行为」唯一定义处（2026-10-23）
 *
 * 音频焦点 / 息屏唤醒 / 耳机拔出 / 快进快退步长，这四件事都是
 * **ExoPlayer 实例级**的配置 —— 绑在实例上，而不是绑在「谁在播」上。
 * 所以它们只该在这里定义一次；别的地方再设一遍就变成两套真相
 * （这个项目已经吃过多次「同一件事两处定义」的亏）。
 *
 * ### 音频焦点（之前完全没有）
 * 不设 `AudioAttributes` 且 `handleAudioFocus=false` 的后果：
 * 别的 App 在放音乐时听匣照放不误；来电、导航提示来了也不打断、不 duck。
 * 开了焦点处理后这些都交给系统。
 *
 * ⚠️ **Android 15（= 本项目 targetSdk 35）的硬要求**：
 * 请求音频焦点时，App 必须是「顶层 App」或「正在跑前台服务」，
 * 否则 `requestAudioFocus()` 直接返回 `AUDIOFOCUS_REQUEST_FAILED` ——
 * **声音会完全出不来**。所以焦点处理和「前台服务已启动」是绑在一起的，
 * 两者必须同时成立。
 *
 * `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`：告诉系统这是「媒体类语音内容」，
 * 系统据此决定混音策略与音量曲线。
 *
 * ### 息屏唤醒（WAKE_LOCK 权限声明了但一直没用）
 * 播放器跑在应用进程的普通优先级上，息屏后 CPU 会被挂起 → 播放中断。
 * `setWakeMode(C.WAKE_MODE_LOCAL)` 让 ExoPlayer 在播放期间持有 CPU 唤醒锁。
 * 权限 `android.permission.WAKE_LOCK` 已在清单里声明 ✓。
 *
 * ### 耳机拔出
 * `setHandleAudioBecomingNoisy(true)`：拔掉耳机自动暂停。
 * 不处理的话，拔了耳机声音还在外放 —— 这是最招人烦的一类音频 App 缺陷。
 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    /**
     * 快退 / 快进步长（毫秒）。
     *
     * 取 App 内 UI 的**既有约定**：全屏播放器的「后退 10 秒 / 快进 30 秒」
     * （`FullScreenPlayerScreen`，图标 Replay10 / Forward30）。
     *
     * 为什么要在播放器层也定一遍：ExoPlayer 的 `seekToPrevious()` /
     * `seekToNext()`（耳机双击、切歌键、系统媒体面板）用的是这两个步长，
     * 不设就用默认值 5s/15s —— 于是「App 里退 10 秒，耳机键退 5 秒」，
     * 同一件事两个步长。**从仓内既有定义 import，不另造常量。**
     */
    const val SEEK_BACK_MS = 10_000L
    const val SEEK_FORWARD_MS = 30_000L

    @Provides
    @Singleton
    fun provideExoPlayer(
        @ApplicationContext context: Context,
        offlineDownloadManager: OfflineDownloadManager,
    ): ExoPlayer {
        val cacheFactory = offlineDownloadManager.buildCacheDataSourceFactory()
        val mediaSourceFactory = DefaultMediaSourceFactory(cacheFactory)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            // 音频焦点：交给系统裁决（见上面 KDoc）
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    // ⚠️ 用 `AUDIO_CONTENT_TYPE_*` 这一族，不是 `CONTENT_TYPE_*`（2026-10-25 修 lint）。
                    //
                    // media3 的 `AudioAttributes.Builder.setContentType()` 的参数注解指向
                    // platform 的 `android.media.AudioAttributes` 常量族 ⇒ 传 `C.CONTENT_TYPE_SPEECH`
                    // 会被 lint 判成 WrongConstant（`lintDebug` 5 个 error 里的第 1 个）。
                    //
                    // 两族的**运行时值完全相同**（javap 实测 media3 1.4.1）：
                    //     AUDIO_CONTENT_TYPE_SPEECH = 1  ==  CONTENT_TYPE_SPEECH = 1
                    //     AUDIO_CONTENT_TYPE_MUSIC  = 2  ==  CONTENT_TYPE_MUSIC  = 2
                    // ⇒ 这是**常量来源**不对，不是行为错误（已发布的 0.4.1 行为不受影响）。
                    // 换成 lint 期望的那一族，值不变，错误消失。
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // 息屏后不中断
            .setWakeMode(C.WAKE_MODE_LOCAL)
            // 拔耳机自动暂停
            .setHandleAudioBecomingNoisy(true)
            // 快进快退步长与 App 内 UI 一致
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
    }
}
