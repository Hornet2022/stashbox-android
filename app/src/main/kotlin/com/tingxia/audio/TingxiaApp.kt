package com.tingxia.audio

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.data.repository.PlaylistRepository
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TingxiaApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var playerController: PlayerController

    @Inject
    lateinit var playlistRepository: PlaylistRepository

    @Inject
    lateinit var articleRepository: ArticleRepository

    /**
     * WorkManager 用 Hilt 注入 Worker（2026-10-02）。
     *
     * 必须在 manifest 里**关掉** WorkManager 的默认初始化
     * （去掉 `androidx.work.WorkManagerInitializer` 那个 meta-data），
     * 否则它会先自建一个不带 Hilt 的 Configuration，我们的 workerFactory
     * 根本不生效 —— 表现是 Worker 注入字段全为 null。
     */
    override fun onCreate() {
        super.onCreate()
        // 播放器的两个依赖在这里接一次（见 PlayerController 里「依赖注入：走 setter」的说明）。
        //
        // 必须在 Application.onCreate 里接，而不是等某个界面 —— 播放可能从
        // 通知栏、迷你条、播放列表任一处发起，那几处拿不到 Hilt 的入口点。
        playerController.setPlaylistRepository(playlistRepository)
        playerController.setArticleRepository(articleRepository)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
            .build()
}
