package com.tingxia.audio.onboarding

import kotlinx.serialization.Serializable
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

/**
 * ⚠️ 2026-10-03 真机验证发现：下面两个响应类原本**都缺 `@Serializable`**。
 *
 * 表现：Retrofit 用的是 `retrofit-converter-kotlinx-serialization`
 * （见 NetworkModule 的 `Json.asConverterFactory`），找不到 serializer 时它
 * 会在**构造请求那一刻**就抛 `Unable to create converter for class
 * java.lang.Object` —— 请求压根没发出去。
 *
 * 而 OnboardingViewModel 把这个异常当 soft-warn 吞了：
 *   `stepViewed failed (soft-warn): Unable to create converter...`
 * 所以功能表现是「点了没反应」，日志里只有一行 W，没有任何 4xx/5xx 可查。
 *
 * 实际影响：`onboarding/step` 和 `onboarding/done` 两个端点**从来没成功调用过**，
 * 后端永远不知道用户完成了引导 → 用户每次冷启动可能又被弹一次引导。
 *
 * 与 R8 无关（未混淆的 debug 包同样失败），但 R8 混淆后日志里类名变成 `a.a`，
 * 一度让人误以为是混淆问题。两条链路一起验才定位到真因。
 */
@Serializable
data class StepResponse(
    val step_viewed: Int,
    val step_name: String,
)

@Serializable
data class DoneResponse(
    val onboarding_done: Boolean,
    val onboarding_done_at: String,
)
