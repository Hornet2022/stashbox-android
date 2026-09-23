package com.tingxia.audio.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp 拦截器：自动为请求附加 `Authorization: Bearer <token>`。
 *
 * - 跳过 `/api/v1/auth/` 端点本身（登录 / 刷新 / 登出），避免递归 / 无 token 时强行加头。
 * - token 取自 [TokenManager.getAccessTokenBlocking]（Dispatchers.IO 上的 runBlocking 封装），
 *   避免在 OkHttp 线程池上直接 runBlocking 阻塞调度线程。
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // 跳过 auth 端点本身（避免循环 / 无谓加头）
        if (originalRequest.url.encodedPath.startsWith("/api/v1/auth/")) {
            return chain.proceed(originalRequest)
        }

        // Dispatchers.IO 避免阻塞 OkHttp 调度线程池（最多 64 线程）
        val token = runBlocking(Dispatchers.IO) { tokenManager.getAccessToken() }
        val request = if (token != null) {
            originalRequest.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            originalRequest
        }
        return chain.proceed(request)
    }
}
