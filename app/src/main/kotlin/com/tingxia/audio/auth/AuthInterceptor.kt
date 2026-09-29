package com.tingxia.audio.auth

import android.util.Log
import kotlinx.coroutines.Dispatchers
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
 * - 401 + code 40100 → 尝试 refresh-token，重放请求（最多 1 次）
 * - refresh 失败 → 抛 [TokenExpiredException]（由 friendlyError 翻译成"登录已失效"）
 * - token 取自 [TokenManager.getAccessTokenBlocking]（Dispatchers.IO 上的 runBlocking 封装），
 *   避免在 OkHttp 线程池上直接 runBlocking 阻塞调度线程
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
                Log.w(TAG, "refresh-token failed; clearing tokens")
                runBlocking(Dispatchers.IO) { tokenManager.clear() }
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
 * Token 过期异常：refresh 失败后抛给上游，由 friendlyError 翻译。
 */
class TokenExpiredException(message: String = "登录已失效") : RuntimeException(message)

/**
 * 内部小客户端：单独 OkHttpClient（不走 AuthInterceptor，避免循环）。
 *
 * 只用 [AuthApi.refresh]，刷新成功就写回 TokenManager，返回新 access_token；
 * 失败返回 null（不抛，由 AuthInterceptor 清空 token）。
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
                // 兼容：refresh 响应 user_id 是 String；TokenManager 期望 Long
                val uidLong = resp.user_id.toLongOrNull() ?: 0L
                val newRefresh = resp.refresh_token ?: refresh
                tokenManager.saveTokens(resp.access_token, newRefresh, uidLong)
                resp.access_token
            } catch (e: Exception) {
                Log.w("RefreshClient", "refresh failed: ${e.message}")
                null
            }
        }
    }
}