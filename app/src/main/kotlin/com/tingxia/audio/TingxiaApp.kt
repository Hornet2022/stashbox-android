package com.tingxia.audio

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TingxiaApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * WorkManager 用 Hilt 注入 Worker（2026-10-02）。
     *
     * 必须在 manifest 里**关掉** WorkManager 的默认初始化
     * （去掉 `androidx.work.WorkManagerInitializer` 那个 meta-data），
     * 否则它会先自建一个不带 Hilt 的 Configuration，我们的 workerFactory
     * 根本不生效 —— 表现是 Worker 注入字段全为 null。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
            .build()
}
