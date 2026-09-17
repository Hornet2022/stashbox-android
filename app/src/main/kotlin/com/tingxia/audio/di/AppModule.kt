package com.tingxia.audio.di

import android.content.Context
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.data.remote.ArticleApi
import com.tingxia.audio.data.repository.ArticleRepository
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
     * TODO(emulator/CP4.4): 模拟器端改用 "http://10.0.2.2:8100/"，
     * 因为模拟器里 localhost 指向模拟器自身而非宿主机。
     */
    private const val BASE_URL = "http://localhost:8100/"

    private val json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            // TODO(CP4.6): 在此处加 Authorization 拦截器（JWT 鉴权）
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
    fun provideArticleRepository(api: ArticleApi): ArticleRepository =
        ArticleRepository(api)

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
