// Media3 把大量播放/缓存 API 标为 @UnstableApi。
// 这些文件直接使用 ExoPlayer / SimpleCache / DownloadManager 等 unstable 声明，
// 必须显式 opt-in，否则 :app:lintDebug 报 UnsafeOptInUsageError 直接让 CI 失败。
//
// 注意用的是 **androidx.annotation.OptIn** 而不是 kotlin.OptIn：Media3 的
// UnstableApi 标的是 androidx 的 @RequiresOptIn，lint 认的是前者；写 Kotlin 的
// @OptIn 只会让 lint 在这行本身上再报一次（实测错误数 16 → 22）。
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
package com.tingxia.audio.audio.di

import androidx.media3.exoplayer.ExoPlayer
import com.tingxia.audio.audio.OfflineDownloadManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * CP11.0.7 P2.1: Hilt EntryPoint，让 [com.tingxia.audio.audio.AudioPlayerService]
 * （非 @AndroidEntryPoint 组件）能从 application context 取到：
 * - [OfflineDownloadManager] 单例（注入 cache data source factory 到 ExoPlayer）
 * - [ExoPlayer] 共享单例（[PlayerModule] 提供，与 [PlayerController] 同一实例，用于绑定 MediaSession）
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AudioPlayerEntryPoint {
    fun offlineDownloadManager(): OfflineDownloadManager
    fun exoPlayer(): ExoPlayer
}