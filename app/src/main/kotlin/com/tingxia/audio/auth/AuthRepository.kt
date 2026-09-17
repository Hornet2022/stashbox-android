package com.tingxia.audio.auth

import javax.inject.Inject

/**
 * 鉴权数据仓库：包装 [AuthApi] 与 [TokenManager]，向 ViewModel 屏蔽网络 / 存储细节。
 *
 * CP4.6 微信登录为 **mock**：[mockWechatLogin] 直接返回固定 token 并落盘，
 * 不真正请求后端（无 OAuth 流程）。CP4.7 接真微信 OAuth 时改为调用 [AuthApi.wechatLogin]。
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
     * Mock 微信登录：返回固定 token（CP4.7 接真 OAuth）。
     * 固定用户便于本地联调；落盘后 [getAccessToken] 立即可用。
     */
    suspend fun mockWechatLogin(): LoginResult {
        val resp = AuthResponse(
            access_token = "mock_access_${MOCK_USER_ID}_cp4_6",
            refresh_token = "mock_refresh_${MOCK_USER_ID}_cp4_6",
            user_id = MOCK_USER_ID,
            expires_in = 3600,
            tier = "free",
        )
        tokenManager.saveTokens(resp.access_token, resp.refresh_token, resp.user_id)
        return LoginResult(resp.access_token, resp.refresh_token, resp.user_id)
    }

    /** 登出：尽力通知服务端后清空本地 token。 */
    suspend fun logout() {
        runCatching { api.logout() }
        tokenManager.clear()
    }

    private companion object {
        const val MOCK_USER_ID = 10001L
    }
}
