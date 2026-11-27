package com.tingxia.audio.auth

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * 用户鉴权 API（user-service，base url 见 [com.tingxia.audio.di.AppModule]）。
 *
 * [wechatLogin] 是**唯一的上线登录路径**（2026-10-02 实测正常；此前注释里的
 * "返回 500" 早已不成立）。[issueToken] 是 dev-only 的联调后门，默认关闭。
 */
interface AuthApi {

    /** POST /api/v1/auth/wechat-login → code 换 token（user-service 按 `wx_<code>` 查/建用户） */
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
     * POST /api/v1/auth/token → gateway **dev-only 后门**：直接传 user_id 拿真 JWT。
     *
     * ⚠️ 该端点 2026-10-02 起默认关闭（需服务端 `STASHBOX_ALLOW_DEV_TOKEN=1`，
     * 且 prod 环境即便设了也 403）。只保留给真机联调需要冒充指定账号的场景。
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
/**
 * 登录请求。
 *
 * @param deviceId **设备指纹**（`DeviceId.androidIdSuffix()`，= SHA-256(ANDROID_ID) 前缀；
 *   取不到时用 `analyticsDeviceId()` 的 `and-…`/`rnd-…`）。
 *   同一台设备恒定 ⇒ 服务端据此生成**唯一用户名**（暂用形态 `d-<机型>_<指纹>`）。
 *   零额外权限：ANDROID_ID 不需要任何运行时权限。
 * @param deviceModel 机型（`Build.MANUFACTURER + Build.MODEL`）⇒ 用户名的"特征性"部分。
 *
 * 两个字段都是**可选**：旧版本客户端不传 ⇒ 服务端回落到 `t-<id>` 形态，唯一性照样成立。
 */
data class WechatLoginRequest(
    val code: String,
    val deviceId: String? = null,
    val deviceModel: String? = null,
)

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
