package com.tingxia.audio.di

import com.tingxia.audio.data.analytics.AnalyticsSink
import com.tingxia.audio.data.analytics.EventReporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 埋点的 DI 接线。
 *
 * 为什么要单独一个模块：[AnalyticsSink] 是接口，实现是 [EventReporter]。
 * 接口与实现分开绑，调用方（播放器 / 首屏计时）就只依赖接口 ——
 * 单测可以直接塞一个记录用的假 sink，不用连 Retrofit 一起造。
 */
@Module
@InstallIn(SingletonComponent::class)
object AnalyticsModule {

    @Provides
    @Singleton
    fun provideAnalyticsSink(reporter: EventReporter): AnalyticsSink = reporter
}
