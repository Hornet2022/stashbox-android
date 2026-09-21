package com.tingxia.audio.data.remote

import com.tingxia.audio.data.model.QuotaResponse
import retrofit2.http.GET

/**
 * CP11.0.4 P1.2 付费墙:
 * 调后端 `GET /api/v1/users/me/quota` 拿配额。
 *
 * api-gateway 自动加 JWT(AuthInterceptor)+ 转发到 user-service。
 */
interface QuotaApi {
    @GET("api/v1/users/me/quota")
    suspend fun getMyQuota(): QuotaResponse
}