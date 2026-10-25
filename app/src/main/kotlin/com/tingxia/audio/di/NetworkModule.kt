package com.tingxia.audio.di

import com.tingxia.audio.auth.AuthApi
import com.tingxia.audio.auth.AuthInterceptor
import com.tingxia.audio.auth.TokenManager
import com.tingxia.audio.data.remote.ArticleApi
import com.tingxia.audio.data.remote.DistillationApi
import com.tingxia.audio.data.remote.EventApi
import com.tingxia.audio.data.remote.FavoritesApi
import com.tingxia.audio.data.remote.FeedbackApi
import com.tingxia.audio.data.remote.MetricsInterceptor
import com.tingxia.audio.data.remote.NotificationApi
import com.tingxia.audio.data.remote.ProgressApi
import com.tingxia.audio.data.remote.QuotaApi
import com.tingxia.audio.data.remote.SubscriptionApi
import com.tingxia.audio.data.remote.TagApi
import com.tingxia.audio.data.remote.TtsApi
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
     * 模拟器里 localhost 指向模拟器自身而非宿主机，emulator 端必须用 10.0.2.2；
     * 真机端用局域网 IP（172.16.5.28）。base url 由 [BaseUrls] 统一提供（P1-3 去硬编码）。
     */
    private val BASE_URL: String = BaseUrls.gatewayBaseUrl()

    private val json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideAuthInterceptor(
        tokenManager: TokenManager,
        refreshClient: com.tingxia.audio.auth.RefreshClient,
    ): AuthInterceptor =
        AuthInterceptor(tokenManager, refreshClient)

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            // CP-ANDROID-MDNS-FALLBACK：mDNS 解析失败时降级到局域网 IP。
            // host 仍是 Mac-mini.local，所以明文白名单不用动（见 MdnsFallbackDns）。
            .dns(MdnsFallbackDns())
            // 主地址是域名，域名有自己的失败模式（备案未过被拦、DNS 没生效、
            // 证书失配）→ 连接层失败时自动切到 IP 入口（与备案无关）。
            // 放在 auth 之前：换 host 不影响 Authorization 头，网关认 token 不认 host。
            .addInterceptor(FallbackBaseUrlInterceptor(BaseUrls.fallbackGatewayBaseUrl))
            // JWT 鉴权：自动附加 Authorization: Bearer ***
            .addInterceptor(authInterceptor)
            // CP11.0.6 P2.2: 响应时间埋点（放在 auth 后，这样可以拿到最终 URL）
            .addInterceptor(MetricsInterceptor())
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                },
            )
            // 接口文档 v1.2 §0.1：全局 read timeout ≥ 35s；
            // §3.1 variants 首次按需转码可能 60s，统一拉到 65s 兼容两种场景。
            // 另设 connect/write 同等超时。
            .connectTimeout(java.time.Duration.ofSeconds(65))
            .readTimeout(java.time.Duration.ofSeconds(65))
            .writeTimeout(java.time.Duration.ofSeconds(65))
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

    // 任务级接口：variants / evaluation / warm（§2.6 / §3.1 / §3.2）
    @Provides
    @Singleton
    fun provideDistillationApi(retrofit: Retrofit): DistillationApi =
        retrofit.create(DistillationApi::class.java)

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
    fun provideSubscriptionApi(retrofit: Retrofit): SubscriptionApi =
        retrofit.create(SubscriptionApi::class.java)

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

    // CP11.0.4 P1.2: 付费墙配额查询
    @Provides
    @Singleton
    fun provideQuotaApi(retrofit: Retrofit): QuotaApi =
        retrofit.create(QuotaApi::class.java)

    // CP-TTS-VOICE: 音色列表 + 音色/语速偏好
    @Provides
    @Singleton
    fun provideTtsApi(retrofit: Retrofit): TtsApi =
        retrofit.create(TtsApi::class.java)

    // 客户端埋点上报（首屏耗时 / 音频启动耗时）
    @Provides
    @Singleton
    fun provideEventApi(retrofit: Retrofit): EventApi =
        retrofit.create(EventApi::class.java)
}