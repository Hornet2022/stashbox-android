package com.tingxia.audio.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * 推送设备注册。
 *
 * 契约（后端 `backend/user-service/main.py`）：
 * - `POST /api/v1/users/me/devices` → 注册/更新一台设备，**对 (token, provider) 幂等**：
 *   同一个组合再次注册 = 把设备改挂到当前账号并重新激活。
 * - `GET /api/v1/users/me/devices` → 当前账号注册过的设备列表。
 * - `DELETE /api/v1/users/me/devices/{device_id}` → 解绑（停用，不硬删）。
 *
 * ⚠️ 这三个接口都**需要鉴权**（后端 `Depends(require_user)`），
 * 跟 [AppUpdateApi] 的公开版本接口不同。
 */

/** 注册请求体。字段名保持 snake_case —— 后端就是这个名字，不做映射。 */
@Serializable
data class DeviceRegisterRequest(
    val token: String,
    val platform: String = "android",
    val provider: String = "none",
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("device_model") val deviceModel: String? = null,
)

/** 一台已注册设备。 */
@Serializable
data class DeviceResponse(
    val id: Int,
    val token: String,
    val platform: String,
    val provider: String,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("device_model") val deviceModel: String? = null,
    @SerialName("is_active") val isActive: Boolean = false,
)

/**
 * 解绑响应。
 *
 * 复用了后端的 `AccountDeleteResponse` 结构（`deleted / already_deleted /
 * anonymized_fields / articles_soft_deleted`）—— 解绑设备走的就是同一个模型。
 */
@Serializable
data class DeviceUnregisterResponse(
    val deleted: Boolean = false,
    @SerialName("already_deleted") val alreadyDeleted: Boolean = false,
    @SerialName("anonymized_fields") val anonymizedFields: List<String> = emptyList(),
    @SerialName("articles_soft_deleted") val articlesSoftDeleted: Int = 0,
)

interface DeviceApi {
    @POST("api/v1/users/me/devices")
    suspend fun registerDevice(@Body body: DeviceRegisterRequest): DeviceResponse

    @GET("api/v1/users/me/devices")
    suspend fun listDevices(): List<DeviceResponse>

    @DELETE("api/v1/users/me/devices/{device_id}")
    suspend fun unregisterDevice(@Path("device_id") deviceId: Int): DeviceUnregisterResponse
}
