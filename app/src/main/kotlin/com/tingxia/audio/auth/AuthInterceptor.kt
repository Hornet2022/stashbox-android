package com.tingxia.audio.auth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp 拦截器：自动为请求附加 `Authorization: Bearer <token>`，并处理 401 自动 refresh。
 *
 * - 跳过 `/api/v1/auth/` 端点本身（登录 / 刷新 / 登出），避免递归
 * - 401 + 手上��� token → 尝试 refresh-token，重放请求（最多 1 次）
 * - refresh 失败 → 清空 token，并向 [SessionExpirySignal] 发一次"登录已失效"
 *
 * ## 为什么必须有这个信号
 *
 * 这里原来在 refresh 失败后只 `Log.w` + `clear()`，然后把**原来的 401 原样返回**，
 * 对外没有任何信号。而 [com.tingxia.audio.ui.auth.AuthViewModel.checkLogin] 只在冷启动
 * 跑一次，AuthState 之后再也没有人重新算过。两者叠起来就是一次静默掉登录：
 * access token 真过期时用户停在文章列表上"看起来还登着"，每个请求都 401，
 * 而且**再也不会被送回登录页** —— 不杀进程就恢复不了。
 *
 * "确定没救了"这件事现在有唯一出口：清完 token 就发 [SessionExpirySignal]，
 * AuthViewModel 收到后把 AuthState 翻回未登录，AuthRoot 重渲染登录页。
 *
 * 只在 refresh **确实失败**时发：refresh 成功并重放成功的普通 401 一律不发，
 * 否则每次正常续期都会把正在用的用户踢下线。
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
    private val refreshClient: RefreshClient,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // 跳过 auth 端点本身
        if (originalRequest.url.encodedPath.startsWith("/api/v1/auth/")) {
            return chain.proceed(originalRequest)
        }

        // 第一次尝试：附 token
        val token = runBlocking(Dispatchers.IO) { tokenManager.getAccessToken() }
        var response = chain.proceed(attachToken(originalRequest, token))

        // 仅在 401 时尝试 refresh 一次（避免 refresh 端点自身 401 引发循环）
        if (response.code == 401 && token != null) {
            val newToken = runBlocking(Dispatchers.IO) {
                refreshClient.refreshIfPossible()
            }
            if (newToken != null) {
                response.close()  // 释放旧的 response body
                response = chain.proceed(attachToken(originalRequest, newToken))
            } else {
                // refresh 也换不出一个可用的会话 → 确定失效。
                //
                // 顺序很重要：先清 token，再发信号。UI 收到信号后会重新判断登录态，
                // 那时必须已经查不到 token，否则 AuthRoot 会把用户又当成已登录
                // 渲染回列表页 —— 正是这个 bug 原来反复出现的形态。
                Log.w(TAG, "refresh failed; clearing tokens and signalling session expiry")
                runBlocking(Dispatchers.IO) { tokenManager.clear() }
                SessionExpirySignal.emit()
            }
        }
        return response
    }

    private fun attachToken(req: okhttp3.Request, token: String?): okhttp3.Request =
        if (token != null) {
            req.newBuilder().addHeader("Authorization", "Bearer $token").build()
        } else {
            req
        }

    companion object {
        private const val TAG = "AuthInterceptor"
    }
}

/**
 * 进程级"登录已失效"信号。
 *
 * ## 为什么是全局单例，而不是某个 ViewModel 的 state
 *
 * 失效发生在 OkHttp 线程上（[AuthInterceptor.intercept]），那一刻没有哪个
 * ViewModel 正在被观察，UI 可能在后台、也可能还没起来。能跨线程、跨生命周期
 * 送达 UI 的载体只有进程级事件流。
 *
 * - **不 replay**：它表达的是"刚刚发生了一次失效"，不是"当前处于失效状态"。
 *   replay 会让冷启动后刚 collect 上的订阅者收到一个陈旧事件，把刚登录好的
 *   用户又踢下线。
 * - **extraBufferCapacity = 1**：没人收时不阻塞（`tryEmit` 直接丢弃）。
 *   丢事件是安全的：失效这件事本身已经被 [TokenManager.clear] 落盘记录，
 *   下次冷启动的 checkLogin 会读到"没 token"；这个信号只负责**当下**把用户
 *   从一个还在显示的已登录界面里救出来。
 * - **可能重复**：并发 401 各自 refresh 一次，都失败就会各发一次（清完 token
 *   之后后续请求手上没 token，不会再进这个分支，所以不会无限重复）。
 *   消费方必须幂等 —— 把 AuthState 置为未登录本来就是幂等的。
 */
object SessionExpirySignal {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    /** 由 [AuthInterceptor] 在**清完 token 之后**调用。 */
    fun emit() {
        _events.tryEmit(Unit)
    }
}

/**
 * Token 过期异常。
 *
 * ⚠️ 当前**没有任何地方抛它**：refresh 失败不再往上抛异常（原注释说"由
 * friendlyError 翻译"是错的，异常从来没离开过拦截器），真实通路是
 * [SessionExpirySignal] + [TokenManager.clear]。留在这里只为不破坏可能引用它的
 * 调用方，新增代码请用 [SessionExpirySignal]。
 */
class TokenExpiredException(message: String = "登录已失效") : RuntimeException(message)

/**
 * 内部小客户端：单独 OkHttpClient（不走 AuthInterceptor，避免循环）。
 *
 * 只用 [AuthApi.refresh]，刷新成功就写回 TokenManager，返回新 access_token；
 * 失败返回 null（不抛，由 AuthInterceptor 清空 token 并发失效信号）。
 */
@Singleton
open class RefreshClient @Inject constructor(
    private val authApiProvider: javax.inject.Provider<AuthApi>,
    private val tokenManager: TokenManager,
) {
    // 互斥：避免并发 401 同时触发多个 refresh
    private val refreshMutex = Mutex()

    /** 暴露给测试覆盖：默认实现调 [AuthApi.refresh]，失败抛被外层捕获 */
    open suspend fun refreshIfPossible(): String? {
        return refreshMutex.withLock {
            try {
                val refresh = tokenManager.getRefreshToken() ?: return@withLock null
                val resp = authApiProvider.get().refresh(RefreshRequest(refresh_token = refresh))
                // user_id 必须是真的，否则宁可这次 refresh 作废。
                //
                // 后端 `users.id` 是 BigInteger 自增主键（从 1 开始，见
                // backend/common/models/user.py），所以 0 / 负数 / 非数字都只可能是
                // "没解析出来"的哨兵值，不是某个真实用户。原来这里 `?: 0L` 会把一个
                // 解析失败直接写进 DataStore，于是这个用户的配额 / 收藏 / 归属全被记到
                // 共享的 user-0 桶上；而 AuthViewModel 只判 `userId != null`，
                // 0 看起来就是个正常会话 —— 损坏完全不可见，还会在下一次 refresh
                // 之前一直是错的。
                val uid = resp.user_id.toLongOrNull()?.takeIf { it > 0L }
                    ?: tokenManager.getUserId()?.takeIf { it > 0L }
                if (uid == null) {
                    // 响应里没有可用身份，本地也没有：写什么都不对。
                    // 写 0 就是上面那个 bug，不写又更新不了 token。
                    // 返回 null 让 AuthInterceptor 走失效分支 —— 至少用户会被送回
                    // 登录页重新登录，而不是静默地错记到 user 0。
                    Log.w("RefreshClient", "refresh returned no usable user_id and none stored")
                    return@withLock null
                }
                val newRefresh = resp.refresh_token ?: refresh
                tokenManager.saveTokens(resp.access_token, newRefresh, uid)
                resp.access_token
            } catch (e: Exception) {
                Log.w("RefreshClient", "refresh failed: ${e.message}")
                null
            }
        }
    }
}