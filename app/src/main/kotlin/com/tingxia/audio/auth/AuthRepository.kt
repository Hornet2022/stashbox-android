package com.tingxia.audio.auth

import android.util.Base64
import com.tingxia.audio.BuildConfig
import org.json.JSONObject
import javax.inject.Inject

/**
 * 鉴权数据仓库：包装 [AuthApi] 与 [TokenManager]，向 ViewModel 屏蔽网络 / 存储细节。
 *
 * 2026-09-22: [mockWechatLogin] 改走 gateway `/api/v1/auth/token`（dev-only mock 端点），
 * 因为 user-service `/api/v1/auth/wechat-login` 当前返回 500。客户端从 JWT payload 解析 user_id。
 * 生产应走 wechatLogin + 真实微信 OAuth（待 user-service 修复后切回）。
 */
class AuthRepository @Inject constructor(
    private val api: AuthApi,
    private val tokenManager: TokenManager,
) {

    /** 当前 access token（无则返回 null）。 */
    suspend fun getAccessToken(): String? = tokenManager.getAccessToken()

    /** 当前 userId（无则返回 null）。 */
    suspend fun getUserId(): Long? = tokenManager.getUserId()

    data class LoginResult(
        val accessToken: String,
        val refreshToken: String,
        val userId: Long,
    )

    /**
     * 走 gateway `/api/v1/auth/token`（dev-only mock）拿真 JWT。
     *
     * - [userId] 留空时回退到 [BuildConfig.DEBUG_USER_ID]（默认 "6892"），
     *   允许 LoginScreen 在 debug 包内手动指定任意 user_id 以便联调多账号。
     * - 响应无 user_id 字段，从 JWT payload 解析（`sub` 字段）
     * - 优先用服务端下发的 refresh_token，缺失才回落占位符（兼容旧服务端）
     *
     * TODO: user-service wechat-login 修复后切回 [AuthApi.wechatLogin]
     */
    suspend fun mockWechatLogin(userId: String? = null): LoginResult {
        val uid = userId?.takeIf { it.isNotBlank() } ?: BuildConfig.DEBUG_USER_ID
        val resp = api.issueToken(TokenIssueRequest(user_id = uid))
        val userIdParsed = parseUserIdFromJwt(resp.access_token)
        // CP 修复：gateway 现下发真实 refresh_token，用它做续期；缺失回落占位符
        val refresh = resp.refresh_token ?: "mock_refresh_${userIdParsed}_cp7_4"
        tokenManager.saveTokens(resp.access_token, refresh, userIdParsed)
        return LoginResult(resp.access_token, refresh, userIdParsed)
    }

    /**
     * 从 JWT payload 解析 `sub` 字段（即 user_id）。
     *
     * JWT 格式：`header.payload.signature`，payload 是 base64url 编码的 JSON。
     * 我们用 android.util.Base64 + org.json 解析，避免引入额外依赖。
     * 手动补 `=` padding 避免不同 Android 版本 Base64 默认行为差异。
     */
    private fun parseUserIdFromJwt(jwt: String): Long {
        return try {
            val payloadB64 = jwt.split(".").getOrNull(1)
                ?: throw IllegalArgumentException("malformed JWT: no payload")
            // base64url 不带 padding，补齐到 4 的倍数
            val padded = payloadB64 + "=".repeat((4 - payloadB64.length % 4) % 4)
            val payloadJson = String(
                Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP),
                Charsets.UTF_8,
            )
            JSONObject(payloadJson).optString("sub", "0").toLong()
        } catch (e: Exception) {
            android.util.Log.w("AuthRepository", "parseUserIdFromJwt failed: ${e.message}")
            0L
        }
    }

    /** 登出：尽力通知服务端后清空本地 token。 */
    suspend fun logout() {
        runCatching { api.logout() }
        tokenManager.clear()
    }
}
