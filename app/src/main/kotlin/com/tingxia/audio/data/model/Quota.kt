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
 * - [Exhausted] remaining <= 0    — 拦截提交,跳 Paywall
 * - [Unknown]   字段缺失 — 放行,避免误拦截
 *
 * CP-QUOTA-ZERO-SEMANTICS：`total <= 0 → Unknown` 是错的。
 *
 * 后端把 `monthly_quota` 的取值分三种（`backend/user-service/main.py`
 * 的 `admin_quota_adjust` docstring + `subscription/plans`）：
 *
 *   -1 = 不限（pro 套餐）
 *    0 = **额度耗尽 / 停用该用户**（CP-USERS-REALITY 明确：0 是系统里的真实值，
 *        管理员把用户设成 0 是合法操作，`consume` 会抛 3001）
 *   >0 = 正常配额
 *
 * 把 0 和 -1 一起当 Unknown，后果是：管理员在后台停用一个用户后，
 * App 端**零提示** —— 配额条不显示（QuotaBanner 同样 `total <= 0 → return`）、
 * 提交前不拦截（Unknown 不拦），用户一直以为一切正常，直到点了「立即剪藏」
 * 被后端 403 才一头雾水。实测链路：
 *
 *   后台配额设为 0 → App 剪藏页无任何提示 → 提交 → 403 {"code":3001}
 *
 * 正确口径：只有 **-1**（不限）才是 Healthy，`0` 是 Exhausted。
 */
enum class QuotaStatus { Healthy, Low, Exhausted, Unknown }

fun QuotaResponse.status(): QuotaStatus {
    val r = remaining ?: return QuotaStatus.Unknown
    val total = monthlyQuota ?: return QuotaStatus.Unknown
    // -1 = 不限（pro）；此时 remaining 无意义，一律按健康处理。
    // 刻意用 `== -1` 而不是 `< 0`：0 是「停用」，必须判为 Exhausted 走拦截。
    if (total < 0) return QuotaStatus.Healthy
    return when {
        total == 0 || r <= 0 -> QuotaStatus.Exhausted
        r * 10 <= total -> QuotaStatus.Low // remaining <= 10% total
        else -> QuotaStatus.Healthy
    }
}