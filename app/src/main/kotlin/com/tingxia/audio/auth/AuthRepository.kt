package com.tingxia.audio.auth

import javax.inject.Inject

/**
 * 鉴权数据仓库：包装 [AuthApi] 与 [TokenManager]，向 ViewModel 屏蔽网络 / 存储细节。
 *
 * CP7.4: [mockWechatLogin] 改调 backend POST /api/v1/auth/wechat-login 拿真 JWT。
 * 不再造假 token (之前 mock token 被 backend require_user 401)。
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
     * CP7.4: 改调 backend 真 wechat-login, 拿真 JWT (之前 mock token 被 backend 401)。
     * 固定用户便于本地联调；落盘后 [getAccessToken] 立即可用。
     * 注意: backend WechatLoginResponse 只有 access_token/user_id/expires_in,
     * 无 refresh_token, 这里用占位符替代。
     */
    suspend fun mockWechatLogin(): LoginResult {
        val resp = api.wechatLogin(WechatLoginRequest(code = "test_cp74_dev_user"))
        // backend 未返回 refresh_token, 用占位符替代
        val userId = resp.user_id.toLongOrNull() ?: 0L
        val refresh = "mock_refresh_${resp.user_id}_cp7_4"
        tokenManager.saveTokens(resp.access_token, refresh, userId)
        return LoginResult(resp.access_token, refresh, userId)
    }

    /** 登出：尽力通知服务端后清空本地 token。 */
    suspend fun logout() {
        runCatching { api.logout() }
        tokenManager.clear()
    }
}
