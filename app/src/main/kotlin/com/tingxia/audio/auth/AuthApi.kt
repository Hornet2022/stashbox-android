package com.tingxia.audio.auth

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * 用户鉴权 API（user-service，base url 见 [com.tingxia.audio.di.AppModule]）。
 *
 * CP4.6 仅 mock 微信登录；[wechatLogin] 真实调用留待 CP4.7 接真微信 OAuth。
 */
interface AuthApi {

    /** POST /api/v1/auth/wechat-login → 微信 code 换 token */
    @POST("api/v1/auth/wechat-login")
    suspend fun wechatLogin(@Body req: WechatLoginRequest): AuthResponse

    /** POST /api/v1/auth/refresh → 用 refresh token 换新 access token */
    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body req: RefreshRequest): AuthResponse

    /** POST /api/v1/auth/logout → 服务端失效 token */
    @POST("api/v1/auth/logout")
    suspend fun logout(): LogoutResponse

    /** GET /api/v1/auth/me → 当前用户信息 */
    @GET("api/v1/auth/me")
    suspend fun me(): UserInfoResponse
}

@Serializable
data class AuthResponse(
    val access_token: String,
    val refresh_token: String? = null,  // backend wechat-login 不返回此字段
    val user_id: String,                // backend 返回 String, 非 Long
    val expires_in: Long,
    val tier: String = "free",
)

@Serializable
data class WechatLoginRequest(val code: String)

@Serializable
data class RefreshRequest(val refresh_token: String)

@Serializable
data class LogoutResponse(val logged_out: Boolean = true)

@Serializable
data class UserInfoResponse(
    val user_id: Long,
    val nickname: String,
    val tier: String,
)
