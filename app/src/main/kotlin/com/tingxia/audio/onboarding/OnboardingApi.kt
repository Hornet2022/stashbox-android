package com.tingxia.audio.onboarding

import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 引导页 API（user-service，base url 见 [com.tingxia.audio.di.AppModule]）。
 *
 * 后端 3 端点已在 stashbox 主仓 06f2f36 推完。
 * CP5.1 客户端半版：补 Android UI 调用。
 */
interface OnboardingApi {

    /** POST /api/v1/users/me/onboarding/start — 进入引导 */
    @POST("api/v1/users/me/onboarding/start")
    suspend fun start()

    /** POST /api/v1/users/me/onboarding/step?step=1|2|3 — 看了 N 步 */
    @POST("api/v1/users/me/onboarding/step")
    suspend fun stepViewed(@Query("step") step: Int): StepResponse

    /** POST /api/v1/users/me/onboarding/done — 完成引导 */
    @POST("api/v1/users/me/onboarding/done")
    suspend fun complete(): DoneResponse
}

data class StepResponse(
    val step_viewed: Int,
    val step_name: String,
)

data class DoneResponse(
    val onboarding_done: Boolean,
    val onboarding_done_at: String,
)
