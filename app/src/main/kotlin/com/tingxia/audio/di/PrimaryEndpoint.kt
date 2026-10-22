package com.tingxia.audio.di

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * 主地址（域名）与备用地址（IP）的**共享**状态。
 *
 * ## 为什么要有这个单例
 * App 里有**三条**独立的网络线，它们各自建自己的 HTTP 栈：
 *
 *   1. Retrofit（主 API）—— [di.NetworkModule] 的 OkHttpClient
 *   2. D9 分享回跳 —— [com.tingxia.audio.share.D9Receiver] 的裸 OkHttpClient
 *   3. 音频播放 / 离线下载 —— Media3 的 `DefaultHttpDataSource`（**不走 OkHttp**）
 *
 * 每条线各自判断「主地址是不是坏的」会得出**互相矛盾**的结论：
 * 3 号线发现域名连不上，但 1 号线不知道 → 1 号线还在一次次试域名。
 * 而「主地址坏掉」这件事是**全局事实**，不该每条线各记一份。
 *
 * 所以熔断状态放在这里，三条线共用：
 *   · 谁发现主地址失败 → [markPrimaryFailed]
 *   · 谁要发请求 → 看 [isPrimaryHealthy]，不健康就直接走备用地址
 *
 * ## 熔断 vs 冷却
 * 这里做的是**熔断 + 冷却**（不是永久禁用）：主地址失败后进入冷却期，
 * 期内直接走备用；冷却过去后重新试主地址（域名可能已经恢复 ——
 * 比如用户换了网络、或备案下来了）。
 */
object PrimaryEndpoint {

    /** 主地址失败后的冷却期（毫秒）。 */
    const val COOLDOWN_MS = 60_000L

    /** 主地址最近一次失败的时刻（毫秒）；0 = 没失败过。 */
    private val primaryFailedAt = AtomicLong(0)

    /**
     * 主地址是否**可用**。
     *
     * 没失败过 → 可用。失败过但在冷却期内 → 不可用（走备用）。
     * 冷却过去 → 又算可用（重新试主地址）。
     */
    fun isPrimaryHealthy(): Boolean {
        val failedAt = primaryFailedAt.get()
        if (failedAt == 0L) return true
        val elapsed = System.currentTimeMillis() - failedAt
        return elapsed !in 0 until COOLDOWN_MS
    }

    /** 记一次主地址失败 → 进入冷却期。 */
    fun markPrimaryFailed() {
        primaryFailedAt.set(System.currentTimeMillis())
    }

    /** 主地址已恢复正常（可选：探测成功后调用，能少走一段冷却期）。 */
    fun markPrimaryHealthy() {
        primaryFailedAt.set(0L)
    }

    /** 冷却期还剩多少毫秒（给日志用）。 */
    fun remainingCooldownMs(): Long {
        val failedAt = primaryFailedAt.get()
        if (failedAt == 0L) return 0L
        return (COOLDOWN_MS - (System.currentTimeMillis() - failedAt)).coerceAtLeast(0L)
    }

    /**
     * 把一个 URL 的 host 换成备用地址的 host（路径与 query 原样保留）。
     *
     * 备用地址为空或非法时**原样返回** —— 兜底是可选的，没有备用地址时
     * 行为必须和以前完全一样。
     */
    fun toFallbackUrl(url: HttpUrl, fallbackBaseUrl: String): HttpUrl {
        val fallback = fallbackBaseUrl.toHttpUrlOrNull() ?: return url
        return url.newBuilder()
            .scheme(fallback.scheme)
            .host(fallback.host)
            .port(fallback.port)
            .build()
    }
}
