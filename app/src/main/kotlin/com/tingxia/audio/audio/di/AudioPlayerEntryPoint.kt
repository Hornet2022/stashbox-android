package com.tingxia.audio.audio.di

import com.tingxia.audio.audio.OfflineDownloadManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * CP11.0.7 P2.1: Hilt EntryPoint，让 [com.tingxia.audio.audio.AudioPlayerService]
 * （非 @AndroidEntryPoint 组件）能从 application context 取到 OfflineDownloadManager 单例，
 * 注入 cache data source factory 到 ExoPlayer RenderersFactory。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AudioPlayerEntryPoint {
    fun offlineDownloadManager(): OfflineDownloadManager
}