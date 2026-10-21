package com.tingxia.audio.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 注销账号接口（2026-10-21）。
 *
 * 服务端契约见 `backend/user-service/main.py` 的
 * `POST /api/v1/users/me/account/delete`。
 *
 * ## 为什么要"打字确认"
 *
 * 注销是**不可逆**的终局操作。服务端因此要求 `confirm_text` 逐字等于
 * `注销听匣`，否则 400（`code 40000`）。客户端必须把这个短语显示给用户，
 * 并在他输入正确之前**不让他按下按钮** —— 否则就退化成"一个随便点的确认弹窗"，
 * 那比不做更危险：用户以为点的是"退出登录"。
 */
@Serializable
data class AccountDeleteRequest(
    /** 必须是 `注销听匣` 四个字的逐字副本。 */
    val confirm_text: String,
    /** 注销原因（可选，方便运营侧归因）。 */
    val reason: String? = null,
)

/**
 * 注销结果。
 *
 * - `deleted`：账号已标记注销（重复调用时依然为 true）
 * - `already_deleted`：这次调用之前账号**已经**注销过 → 客户端仍要照常登出
 * - `anonymized_fields`：被匿名化的字段名（open_id / nickname / email …）
 * - `articles_soft_deleted`：被软删的文章数量
 */
@Serializable
data class AccountDeleteResponse(
    val deleted: Boolean = false,
    val already_deleted: Boolean = false,
    val anonymized_fields: List<String> = emptyList(),
    val articles_soft_deleted: Int = 0,
)

interface AccountApi {

    /**
     * 注销（关闭）当前账号。
     *
     * 幂等：重复调用返回 200 + `already_deleted=true`，不报错 ——
     * 客户端重试（网络抖动）不该让用户看到"注销失败"。
     */
    @POST("api/v1/users/me/account/delete")
    suspend fun deleteAccount(@Body body: AccountDeleteRequest): AccountDeleteResponse
}
