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

    /** POST /api/v1/auth/wechat-login → 微信 code 换 token（user-service 实现，当前 500） */
    @POST("api/v1/auth/wechat-login")
    suspend fun wechatLogin(@Body req: WechatLoginRequest): AuthResponse

    /** POST /api/v1/auth/refresh-token → 用 refresh token 换新 access token（网关路由到 user-service） */
    @POST("api/v1/auth/refresh-token")
    suspend fun refresh(@Body req: RefreshRequest): AuthResponse

    /** POST /api/v1/auth/logout → 服务端失效 token */
    @POST("api/v1/auth/logout")
    suspend fun logout(): LogoutResponse

    /** GET /api/v1/auth/me → 当前用户信息 */
    @GET("api/v1/auth/me")
    suspend fun me(): UserInfoResponse

    /**
     * POST /api/v1/auth/token → gateway dev-only mock：直接传 user_id 拿真 JWT。
     *
     * 仅供 Android 端本地联调，绕过 user-service wechat-login 500 错误（2026-09-22 发现）。
     * 生产应走 wechatLogin + 真实微信 OAuth。
     */
    @POST("api/v1/auth/token")
    suspend fun issueToken(@Body req: TokenIssueRequest): TokenIssueResponse
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

/** POST /api/v1/auth/token 请求体（gateway dev-only mock）。 */
@Serializable
data class TokenIssueRequest(val user_id: String)

/**
 * POST /api/v1/auth/token 响应体（gateway dev-only mock）。
 *
 * 注意：响应没有 user_id 字段，客户端需从 JWT 解析（payload.sub）。
 */
@Serializable
data class TokenIssueResponse(
    val access_token: String,
    val refresh_token: String? = null,
    val expires_in: Int,
)
