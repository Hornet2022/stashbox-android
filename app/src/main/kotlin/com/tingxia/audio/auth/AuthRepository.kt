package com.tingxia.audio.auth

import android.util.Base64
import com.tingxia.audio.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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

    /**
     * 登出：尽力通知服务端，然后 **无条件** 清空本地 token。
     *
     * 这里原来写的是 `runCatching { api.logout() }` + `tokenManager.clear()`，
     * 有一个必然踩中的坑：`runCatching` 捕获 `Throwable`，连 `CancellationException`
     * 一起吞掉；而 tokenManager.clear() 走 DataStore 写盘，在协程已取消时会在第一个
     * 挂起点再次抛 CancellationException —— 于是本地 token 永远清不掉，
     * 表现为「点了退出登录，账户还登着」。
     *
     * 取消也可能来自"用户点完就离开该页面"（协程作用域被回收），所以：
     * - 被取消 → 在 NonCancellable 里清完 token 再把取消信号继续抛上去（不吞协程契约）
     * - 普通网络失败 → 服务端注销失败不该阻塞本地登出，照样清
     */
    suspend fun logout() {
        try {
            api.logout()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { tokenManager.clear() }
            throw e
        } catch (_: Exception) {
            // 服务端注销失败不阻塞本地登出
        }
        tokenManager.clear()
    }
}
