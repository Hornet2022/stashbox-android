package com.tingxia.audio.di

import android.content.Context
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.auth.AuthApi
import com.tingxia.audio.auth.AuthInterceptor
import com.tingxia.audio.auth.AuthRepository
import com.tingxia.audio.auth.TokenManager
import com.tingxia.audio.data.remote.ArticleApi
import com.tingxia.audio.data.remote.TagApi
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.data.repository.TagRepository
import com.tingxia.audio.onboarding.OnboardingApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * 后端统一入口（api-gateway，dev 端口 8100）。
     *
     * 模拟器里 localhost 指向模拟器自身而非宿主机，emulator 端必须用 10.0.2.2。
     * 真机端用局域网 IP（待 CP4.7-A2 处理）。
     *
     * CP4.7-A1: 切到 emulator 默认值（10.0.2.2）。
     */
    private const val BASE_URL = "http://10.0.2.2:8100/"

    private val json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideTokenManager(@ApplicationContext context: Context): TokenManager =
        TokenManager(context)

    @Provides
    @Singleton
    fun provideAuthInterceptor(tokenManager: TokenManager): AuthInterceptor =
        AuthInterceptor(tokenManager)

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            // JWT 鉴权：自动附加 Authorization: Bearer <token>（CP4.6）
            .addInterceptor(authInterceptor)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                },
            )
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideArticleApi(retrofit: Retrofit): ArticleApi =
        retrofit.create(ArticleApi::class.java)

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi =
        retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideOnboardingApi(retrofit: Retrofit): OnboardingApi =
        retrofit.create(OnboardingApi::class.java)

    @Provides
    @Singleton
    fun provideTagApi(retrofit: Retrofit): TagApi =
        retrofit.create(TagApi::class.java)

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
