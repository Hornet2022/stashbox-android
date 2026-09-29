package com.tingxia.audio.data.model

import kotlinx.serialization.Serializable

/**
 * CP11.0.4 P1.2 付费墙:
 * 后端 `GET /api/v1/users/me/quota` 返回结构。
 *
 * 字段全 nullable,避免后端任一字段缺失导致反序列化失败
 * (同 CP10.5 CreateArticleResponse 的设计思路)。
 *
 * CP-QUOTA-FIELD：原来读 `used_quota`，后端 `_quota_payload` 返的却是
 * `quota_used`（`backend/user-service/main.py`）。字段名对不上 →
 * `usedQuota` 恒为 null → 付费墙上「已用」永远显示 0，用户完全不知道
 * 自己配额用掉了多少。已按后端真实字段名改正。
 *
 * 另外后端**根本不返回 plan**（users 表只有一列 tier，既是等级也是管理员角色，
 * 没有独立的套餐字段），所以 `plan` 恒为 null。保留字段但不再拿它编造套餐名，
 * 见 `PaywallScreen` 的显示口径。
 *
 * 服务端契约见 backend/user-service/main.py `_quota_payload`
 */
@Serializable
data class QuotaResponse(
    @kotlinx.serialization.SerialName("monthly_quota")
    val monthlyQuota: Int? = null,
    @kotlinx.serialization.SerialName("quota_used")
    val usedQuota: Int? = null,
    @kotlinx.serialization.SerialName("remaining")
    val remaining: Int? = null,
    @kotlinx.serialization.SerialName("reset_at")
    val resetAt: String? = null,
)

/**
 * 配额"快用完 / 用尽"判断。
 *
 * 状态机:
 * - [Healthy]   remaining > 10% — 正常
 * - [Low]       remaining <= 10% — UI 变橙色(见 P3.1)
 * - [Exhausted] remaining == 0    — 拦截提交,跳 Paywall
 *
 * remaining 是 NaN(null)时 — 视为 Healthy,避免误拦截。
 */
enum class QuotaStatus { Healthy, Low, Exhausted, Unknown }

fun QuotaResponse.status(): QuotaStatus {
    val r = remaining ?: return QuotaStatus.Unknown
    val total = monthlyQuota ?: return QuotaStatus.Unknown
    if (total <= 0) return QuotaStatus.Unknown
    return when {
        r <= 0 -> QuotaStatus.Exhausted
        r * 10 <= total -> QuotaStatus.Low       // remaining <= 10% total
        else -> QuotaStatus.Healthy
    }
}