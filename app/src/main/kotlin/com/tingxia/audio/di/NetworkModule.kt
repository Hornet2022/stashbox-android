package com.tingxia.audio.di

import com.tingxia.audio.auth.AuthApi
import com.tingxia.audio.auth.AuthInterceptor
import com.tingxia.audio.auth.TokenManager
import com.tingxia.audio.data.remote.ArticleApi
import com.tingxia.audio.data.remote.FavoritesApi
import com.tingxia.audio.data.remote.FeedbackApi
import com.tingxia.audio.data.remote.NotificationApi
import com.tingxia.audio.data.remote.ProgressApi
import com.tingxia.audio.data.remote.TagApi
import com.tingxia.audio.onboarding.OnboardingApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * 后端统一入口（api-gateway，dev 端口 8100）。
     *
     * 模拟器里 localhost 指向模拟器自身而非宿主机，emulator 端必须用 10.0.2.2。
     * 真机端用局域网 IP（待 CP4.7-A2 处理）。
     *
     * CP4.7-A1: 切到 emulator 默认值（10.0.2.2）。
     */
    private const val BASE_URL = "http://172.16.5.28:8100/"

    private val json = Json { ignoreUnknownKeys = true }

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
    fun provideNotificationApi(retrofit: Retrofit): NotificationApi =
        retrofit.create(NotificationApi::class.java)

    @Provides
    @Singleton
    fun provideFavoritesApi(retrofit: Retrofit): FavoritesApi =
        retrofit.create(FavoritesApi::class.java)

    @Provides
    @Singleton
    fun provideFeedbackApi(retrofit: Retrofit): FeedbackApi =
        retrofit.create(FeedbackApi::class.java)

    @Provides
    @Singleton
    fun provideProgressApi(retrofit: Retrofit): ProgressApi =
        retrofit.create(ProgressApi::class.java)
}
