package com.tingxia.audio.onboarding

import javax.inject.Inject

/**
 * 引导页数据仓库：包装 [OnboardingApi]，向 ViewModel 屏蔽网络细节。
 *
 * CP5.1 客户端半版：3 步引导 + start/step/done 调用。
 * 引导状态本地存 SharedPreferences（onboarding_done 持久化见 CP6.7 服务端化）。
 */
class OnboardingRepository @Inject constructor(
    private val api: OnboardingApi,
) {

    suspend fun start() = api.start()

    suspend fun stepViewed(step: Int) = api.stepViewed(step)

    suspend fun complete() = api.complete()
}
