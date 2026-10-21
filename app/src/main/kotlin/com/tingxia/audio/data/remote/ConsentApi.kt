package com.tingxia.audio.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

/**
 * 隐私政策 + 同意记录接口（2026-10-21）。
 *
 * 服务端契约见 `backend/user-service/main.py`：
 * - `GET  /api/v1/policies/privacy` —— **公开端点**（未登录也要能读政策）
 * - `GET  /api/v1/users/me/consent` —— 需要登录
 * - `PUT  /api/v1/users/me/consent` —— 需要登录
 *
 * ## 为什么要把政策正文放在服务端
 *
 * 版本号和正文不会走散：客户端显示"你同意的是 v1"时，服务端给的就是 v1 的正文。
 * 两边各存一份迟早会出现"客户端说 v1、服务端已经 v2"的错配。
 * 政策改版也不用发版 —— 改正文 + 改版本号，客户端下次启动拉最新的就行。
 */
@Serializable
data class PrivacyPolicyResponse(
    val version: String = "",
    val updated_at: String = "",
    val title: String = "",
    /** 正文是 **Markdown**，客户端负责渲染成可读文本。 */
    val content: String = "",
)

/**
 * 同意状态。
 *
 * 字段全部给了默认值：任何一个字段缺失都不该让整个反序列化失败
 * （同 `data/model/Quota.kt` 的设计思路 —— 后端加字段不该让老客户端崩掉）。
 */
@Serializable
data class ConsentStateResponse(
    /** 用户**已同意**的版本号（没同意过时服务端返空串）。 */
    val privacy_policy_version: String = "",
    val privacy_policy_accepted: Boolean = false,
    val privacy_policy_accepted_at: String? = null,
    val personalization_enabled: Boolean = false,
    val cross_user_share_enabled: Boolean = false,
)

/**
 * 写同意记录。
 *
 * `personalization_enabled` / `cross_user_share_enabled` 是**可选**的
 * （服务端是 `bool | None`）：不传 = 不改动这两项。
 *
 * 同意页面只想记"同意政策"这一件事，所以这里默认两者都不传 ——
 * 顺手把个性化开关改成 true，等于替用户做了他没做的选择。
 */
@Serializable
data class ConsentUpdateRequest(
    /** 用户实际看过的那一版政策版本号。版本不对服务端直接 409。 */
    val privacy_policy_version: String,
    val accepted: Boolean,
    val personalization_enabled: Boolean? = null,
    val cross_user_share_enabled: Boolean? = null,
)

interface ConsentApi {

    /**
     * 当前隐私政策正文。
     *
     * **公开端点** —— 用户还没登录时也要能读政策（首次启动的同意页就在登录之前）。
     * 服务端读不到政策文件时返回 503（`privacy policy unavailable`）。
     */
    @GET("api/v1/policies/privacy")
    suspend fun privacyPolicy(): PrivacyPolicyResponse

    /** 我当前的同意状态。客户端用它决定"要不要弹同意页"。 */
    @GET("api/v1/users/me/consent")
    suspend fun consentState(): ConsentStateResponse

    /**
     * 记录 / 撤回同意。
     *
     * 允许**撤回**（`accepted=false` 是合法操作）。
     *
     * ⚠️ 提交的版本号必须与服务端当前版本一致，否则 **409**：
     * 客户端不能拿着旧版本号来"同意"，那等于同意了一份用户没看过的政策。
     */
    @PUT("api/v1/users/me/consent")
    suspend fun updateConsent(@Body body: ConsentUpdateRequest): ConsentStateResponse
}
