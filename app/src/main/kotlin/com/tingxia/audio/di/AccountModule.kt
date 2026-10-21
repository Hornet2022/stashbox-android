package com.tingxia.audio.di

import android.content.Context
import com.tingxia.audio.auth.TokenManager
import com.tingxia.audio.data.local.UserDataCleaner
import com.tingxia.audio.data.remote.AccountApi
import com.tingxia.audio.data.remote.ConsentApi
import com.tingxia.audio.data.repository.AccountRepository
import com.tingxia.audio.data.repository.ConsentLocalStore
import com.tingxia.audio.data.repository.ConsentRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * 账号合规（隐私政策同意 + 注销账号）的依赖装配（2026-10-21）。
 *
 * ## 为什么单独一个 Module
 *
 * `NetworkModule` / `AppModule` 都有别的 agent 在动，这组依赖（新端点 + 新仓库）
 * 单独住一个文件，两边都不用改 —— 合并冲突面最小。
 *
 * 装配方式和现有模块一致：Retrofit 造接口，仓库包接口。
 */
@Module
@InstallIn(SingletonComponent::class)
object AccountModule {

    @Provides
    @Singleton
    fun provideAccountApi(retrofit: Retrofit): AccountApi =
        retrofit.create(AccountApi::class.java)

    @Provides
    @Singleton
    fun provideConsentApi(retrofit: Retrofit): ConsentApi =
        retrofit.create(ConsentApi::class.java)

    @Provides
    @Singleton
    fun provideAccountRepository(
        api: AccountApi,
        tokenManager: TokenManager,
        userDataCleaner: UserDataCleaner,
        consentLocalStore: ConsentLocalStore,
    ): AccountRepository = AccountRepository(api, tokenManager, userDataCleaner, consentLocalStore)

    @Provides
    @Singleton
    fun provideConsentRepository(api: ConsentApi): ConsentRepository =
        ConsentRepository(api)

    @Provides
    @Singleton
    fun provideConsentLocalStore(
        @ApplicationContext context: Context,
    ): ConsentLocalStore = ConsentLocalStore(context)
}
