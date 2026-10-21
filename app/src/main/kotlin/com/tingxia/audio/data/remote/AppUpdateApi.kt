package com.tingxia.audio.data.remote

import com.tingxia.audio.data.model.AppVersionResponse
import retrofit2.http.GET

/**
 * 应用内更新：读取服务端当前发布的版本信息。
 *
 * `GET /api/v1/app/version` 是**公开端点**（网关路由表里注册为 public），
 * 不带 Authorization 也能取到 —— 更新检查发生在"还没登录"和"刚启动"的时候，
 * 那时可能根本没有 token。
 *
 * ⚠️ 成功响应是**裸对象**，不是 `{code,message,data}` 信封；
 * 信封只用于错误响应（网关的 4xx/5xx）。所以这里直接返回对象，
 * 错误靠 Retrofit 抛 HttpException，再由 `parseEnvelope` 还原成 ApiException。
 */
interface AppUpdateApi {
    @GET("api/v1/app/version")
    suspend fun getLatestVersion(): AppVersionResponse
}
