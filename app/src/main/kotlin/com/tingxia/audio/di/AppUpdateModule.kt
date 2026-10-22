package com.tingxia.audio.di

import android.content.Context
import com.tingxia.audio.data.remote.AppUpdateApi
import com.tingxia.audio.data.remote.DeviceApi
import com.tingxia.audio.data.repository.AppUpdateRepository
import com.tingxia.audio.data.repository.ContextDeviceIdStore
import com.tingxia.audio.data.repository.DeviceIdStore
import com.tingxia.audio.data.repository.DeviceRepository
import com.tingxia.audio.ui.update.ApkInstaller
import com.tingxia.audio.ui.update.SystemApkInstaller
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * 标记"下载 APK 用的 OkHttpClient"。
 *
 * 为什么不能直接复用 [NetworkModule] 提供的那个 [OkHttpClient]：
 * - 那个客户端挂着鉴权拦截器，APK 下载地址（蒲公英 / CDN）不需要也不该带 JWT；
 * - 它的 read timeout 是 65s（后端接口的口径），APK 动辄几十 MB，
 *   慢一点就会中途断流，表现为"下载到 80% 失败"；
 * - 更要命的是：CDN 返回 401 时，鉴权拦截器会**清掉用户的登录 token** 并发
 *   "会话失效"信号 —— 一个下载失败把用户踢下线，这绝对不能发生。
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApkDownloadClient

/**
 * 应用内更新 + 推送设备注册 的依赖装配。
 *
 * 单独开一个模块（而不是塞进 NetworkModule / AppModule）：
 * 那两个文件另有同事在改，这里**只加不改**，冲突面最小。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppUpdateModule {

    @Provides
    @Singleton
    fun provideAppUpdateApi(retrofit: Retrofit): AppUpdateApi =
        retrofit.create(AppUpdateApi::class.java)

    @Provides
    @Singleton
    fun provideDeviceApi(retrofit: Retrofit): DeviceApi =
        retrofit.create(DeviceApi::class.java)

    @Provides
    @Singleton
    fun provideAppUpdateRepository(api: AppUpdateApi): AppUpdateRepository =
        AppUpdateRepository(api)

    @Provides
    @Singleton
    fun provideDeviceIdStore(@ApplicationContext context: Context): DeviceIdStore =
        ContextDeviceIdStore(context)

    @Provides
    @Singleton
    fun provideDeviceRepository(api: DeviceApi, deviceIdStore: DeviceIdStore): DeviceRepository =
        DeviceRepository(api, deviceIdStore)

    @Provides
    @Singleton
    @ApkDownloadClient
    fun provideApkDownloadClient(): OkHttpClient =
        OkHttpClient.Builder()
            // APK 放在**咱们自己的域名**上（/downloads/xxx.apk），
            // 所以它同样会撞上「域名连不上」那类故障 —— 这个 client 不走
            // Retrofit，拿不到 NetworkModule 那个兜底拦截器，得自己加一份。
            .addInterceptor(FallbackBaseUrlInterceptor(BaseUrls.fallbackGatewayBaseUrl))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()

    @Provides
    @Singleton
    fun provideApkInstaller(
        @ApplicationContext context: Context,
        @ApkDownloadClient client: OkHttpClient,
    ): ApkInstaller = SystemApkInstaller(context, client)
}
