package com.tingxia.audio.data.model

import kotlinx.serialization.Serializable

/**
 * CP11.0.4 P1.2 付费墙:
 * 后端 `GET /api/v1/users/me/quota` 返回结构。
 *
 * 字段全 nullable,避免后端任一字段缺失导致反序列化失败
 * (同 CP10.5 CreateArticleResponse 的设计思路)。
 *
 * 服务端契约见 backend/user-service/main.py:213 `UserQuotaResponse`
 */
@Serializable
data class QuotaResponse(
    @kotlinx.serialization.SerialName("plan")
    val plan: String? = null,           // "free" | "premium"
    @kotlinx.serialization.SerialName("monthly_quota")
    val monthlyQuota: Int? = null,
    @kotlinx.serialization.SerialName("used_quota")
    val usedQuota: Int? = null,
    @kotlinx.serialization.SerialName("remaining")
    val remaining: Int? = null,
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