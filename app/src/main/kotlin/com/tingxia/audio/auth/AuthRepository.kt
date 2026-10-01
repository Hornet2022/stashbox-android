package com.tingxia.audio.auth

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

/**
 * 鉴权数据仓库：包装 [AuthApi] 与 [TokenManager]，向 ViewModel 屏蔽网络 / 存储细节。
 *
 * ## 登录路径（2026-10-02 修正）
 *
 * 之前这里只有一条路：走 gateway 的 `/api/v1/auth/token` 签发 mock JWT，注释说
 * 「user-service 的 wechat-login 当前返回 500」。**那个 500 早已不复存在** —— 实测
 * `wechat-login` 正常返回，因此本类改回以 [wechatLogin] 为唯一真实登录路径。
 *
 * 走 `issueToken` 的那个端点是一个**无鉴权后门**：任何能访问网关的人传任意
 * user_id 就能拿到该账号的真 token。它已改为默认关闭（需服务端显式设
 * `STASHBOX_ALLOW_DEV_TOKEN=1`，且 prod 环境即便设了也拒绝），所以这里只把它
 * 保留成**显式的联调通道**，供需要在真机上冒充指定账号的开发者使用。
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
        val refreshToken: String?,
        val userId: Long,
    )

    /**
     * 真实登录：微信 code 换 token（user-service 实现，按 code 派生稳定 open_id）。
     *
     * 这是**上线后的唯一登录路径**。code 在联调期可传任意稳定串
     * （如 `dev_user_1`），服务端会按 `wx_<code>` 查/建用户。
     */
    suspend fun wechatLogin(code: String): LoginResult {
        val resp = api.wechatLogin(WechatLoginRequest(code))
        val uid = resp.user_id.toLongOrNull()
            ?: throw IllegalStateException("服务端返回的 user_id 非法: ${resp.user_id}")
        // wechat-login 实际会下发 refresh_token（早期注释说"不返回"已过时）。
        // 缺了就直接失败 —— 拿不到 refresh 意味着 access 过期后无法续期，
        // 静默存个占位符只会让用户几小时后莫名其妙被登出。
        val refresh = resp.refresh_token
            ?: throw IllegalStateException("服务端未下发 refresh_token，无法保证会话续期")
        tokenManager.saveTokens(resp.access_token, refresh, uid)
        return LoginResult(resp.access_token, refresh, uid)
    }

    /**
     * 联调专用：直接以指定 user_id 换取 token。
     *
     * ⚠️ 依赖 gateway 的 dev-only 端点，该端点**默认关闭**。服务端没设
     * `STASHBOX_ALLOW_DEV_TOKEN=1` 时会返回 403 —— 这是预期行为，请让开发者
     * 显式开启，而不是把这里改回无条件调用。
     *
     * 仅在真机联调需要冒充某个已有账号（例如带历史数据的 user 1）时使用。
     */
    suspend fun devImpersonate(userId: String): LoginResult {
        val resp = api.issueToken(TokenIssueRequest(user_id = userId))
        val parsed = parseUserIdFromJwt(resp.access_token)
        val refresh = resp.refresh_token ?: "dev_refresh_${parsed}"
        tokenManager.saveTokens(resp.access_token, refresh, parsed)
        return LoginResult(resp.access_token, refresh, parsed)
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
