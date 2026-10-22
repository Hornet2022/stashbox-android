package com.tingxia.audio.audio

import com.tingxia.audio.di.PrimaryEndpoint
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 兜底地址的**决策逻辑**（纯函数，不碰 android.* → JVM 单测可测）。
 *
 * Media3 的 [androidx.media3.datasource.DataSpec] 用的是 `android.net.Uri`，
 * 那个类在 JVM 单测里是 stub（`Uri.parse` 返回 null）→ 直接测
 * [FallbackHttpDataSource] 测不了。所以把「该用哪个地址」「这次失败要不要
 * 标记熔断」这两件事抽到这里，行为由单测锁住；集成部分靠真机验证。
 */
object FallbackUrlPolicy {

    /**
     * 这次请求该用哪个地址。
     *
     * · 熔断中（主地址刚失败过）→ 用备用地址
     * · 否则 → 用主地址
     */
    fun resolve(primary: HttpUrl, fallbackBaseUrl: String): HttpUrl {
        if (PrimaryEndpoint.isPrimaryHealthy()) return primary
        return PrimaryEndpoint.toFallbackUrl(primary, fallbackBaseUrl)
    }

    /**
     * 这次失败要不要标记「主地址坏了」。
     *
     * 只认**连接层**的失败：
     *   · 连接被拒 / DNS 解析不了 / 证书握手失败 → 主地址有问题
     *   · 读超时 → 服务端慢，换地址也慢 → 不标记（否则会误伤整个会话）
     *   · HTTP 4xx/5xx → 服务端的回答，跟主地址通不通无关 → 不标记
     */
    fun shouldMarkPrimaryFailed(cause: Throwable?): Boolean {
        val io = cause as? java.io.IOException ?: return false
        if (io is java.net.SocketTimeoutException) return false
        return true
    }

    /** 备用地址是否可用（配了、且合法）。 */
    fun hasUsableFallback(fallbackBaseUrl: String): Boolean =
        fallbackBaseUrl.toHttpUrlOrNull() != null
}
