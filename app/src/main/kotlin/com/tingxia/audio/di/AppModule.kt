package com.tingxia.audio.di

import android.content.Context
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.auth.AuthApi
import com.tingxia.audio.auth.AuthRepository
import com.tingxia.audio.auth.TokenManager
import com.tingxia.audio.data.remote.ArticleApi
import com.tingxia.audio.data.remote.FavoritesApi
import com.tingxia.audio.data.remote.FeedbackApi
import com.tingxia.audio.data.remote.NotificationApi
import com.tingxia.audio.data.remote.TagApi
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.data.repository.FeedbackRepository
import com.tingxia.audio.data.repository.FavoritesRepository
import com.tingxia.audio.data.repository.NotificationRepository
import com.tingxia.audio.data.repository.TagRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideTokenManager(@ApplicationContext context: Context): TokenManager =
        TokenManager(context)

    @Provides
    @Singleton
    fun provideTagRepository(api: TagApi): TagRepository =
        TagRepository(api)

    @Provides
    @Singleton
    fun provideArticleRepository(api: ArticleApi): ArticleRepository =
        ArticleRepository(api)

    @Provides
    @Singleton
    fun provideNotificationRepository(api: NotificationApi): NotificationRepository =
        NotificationRepository(api)

    @Provides
    @Singleton
    fun provideFavoritesRepository(api: FavoritesApi): FavoritesRepository =
        FavoritesRepository(api)

    @Provides
    @Singleton
    fun provideFeedbackRepository(api: FeedbackApi): FeedbackRepository =
        FeedbackRepository(api)

    @Provides
    @Singleton
    fun provideAuthRepository(
        api: AuthApi,
        tokenManager: TokenManager,
    ): AuthRepository = AuthRepository(api, tokenManager)

    /**
     * 播放控制器单例（CP4.4）。
     * 内部持有 ExoPlayer，暴露 StateFlow 给 Compose UI。
     * AudioPlayerService 由系统启动，不走 Hilt，因此不在此处注入。
     */
    @Provides
    @Singleton
    fun providePlayerController(@ApplicationContext context: Context): PlayerController =
        PlayerController(context)
}
