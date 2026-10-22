package com.tingxia.audio.di

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

/**
 * 主地址连不上时的兜底切换。
 *
 * ## 为什么需要它
 * 主地址是**域名**（api.stashbox.cn），而域名这条链路上有域名自己的失败模式：
 * ICP 备案未过被拦、DNS 没生效、证书过期/失配、用户所在网络把到域名的
 * TLS 连接重置（企业 WiFi / 运营商的 HTTPS 过滤）……
 * 这些都不该让内测用户看到一个「连不上」的 App。
 *
 * IP 入口（https://8.148.9.151/）**与备案无关**（IP 证书不需要备案），
 * 是唯一一条不受上面这些影响的路径 → 主地址失败时用它兜底。
 *
 * ## 两层机制
 *
 * ### ① 切换：什么时候该换地址重试
 * 切分依据是**幂等性**，不是异常类型 —— 异常类型看不出「请求有没有已经
 * 发出去」，只有幂等性能判断：
 *
 *   · **幂等方法**（GET/HEAD/OPTIONS/PUT/DELETE，按 HTTP 规范幂等）→
 *     任何失败都值得换地址再试一次。
 *   · **幂等 POST**（白名单）→ 同上。白名单按**后端语义**逐个确认过：
 *     登录/发 token、剪藏（CP-DUPLICATE-CLIP 查重复用已有文章、不重复计费）、
 *     收藏（UNIQUE 约束保证）、进度上报（覆盖写）、设备登记（upsert）。
 *     少了这些，在会 RST 掉域名的网络里**连登录、隐私同意都过不去**
 *     （登录是 POST、隐私同意是 PUT）→ App 直接不可用。
 *   · **不幂等的 POST**（评分、反馈、手动触发蒸馏）→ 只在「连接阶段就失败」
 *     时切（DNS 解析不了、连不上、证书握手失败）—— 这三种情况下请求
 *     **肯定还没发出去**。其余情况（读超时、连接被重置）请求可能已经到达
 *     服务端，换地址重试等于把同一个操作提交两遍 → 不切。
 *   · **读超时一律不切**：那是服务端慢，换地址大概率也慢，白等一个 timeout。
 *
 * ### ② 熔断：失败过一次就别再逐个试
 * 光有切换不够：主地址坏掉时，**每一个**请求都要先失败一次才切到备用
 * 地址 —— 用户看到的是「每个按钮都要等一次失败」。而且那些**不幂等**的
 * 写操作（评分、反馈）第一次就是这么失败的。
 *
 * 所以主地址一旦失败，就在 [COOLDOWN_MS] 内**直接走备用地址**，不再试
 * 主地址。等这一段过去再重新试主（域名可能已经恢复）。
 *
 * ⚠️ 这只影响「主地址不可用」这一种情况：主地址正常时行为完全不变。
 */
class FallbackBaseUrlInterceptor(
    private val fallbackBaseUrl: String,
) : Interceptor {

    /** 主地址最近一次失败的时刻（毫秒）；0 = 没失败过。 */
    private val primaryFailedAt = AtomicLong(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fallback: HttpUrl = fallbackBaseUrl.toHttpUrlOrNull()
            ?: return chain.proceed(request)

        // 熔断中：主地址刚失败过，直接走备用地址（省掉一次必然失败的往返）
        if (isPrimaryCircuitOpen()) {
            val switched = switchTo(request, fallback)
            Log.w(TAG, "主地址处于熔断期，直接走备用地址：${request.url} → $switched")
            return chain.proceed(request.newBuilder().url(switched).build())
        }

        return try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (!shouldSwitch(request, e)) throw e
            primaryFailedAt.set(System.currentTimeMillis())
            val switched = switchTo(request, fallback)
            Log.w(
                TAG,
                "主地址失败（${e.javaClass.simpleName}: ${e.message}），切到备用地址：${request.url} → $switched",
                e,
            )
            chain.proceed(request.newBuilder().url(switched).build())
        }
    }

    /** 熔断是否打开（主地址刚失败过，且还在冷却期内）。 */
    private fun isPrimaryCircuitOpen(): Boolean {
        val failedAt = primaryFailedAt.get()
        if (failedAt == 0L) return false
        val elapsed = System.currentTimeMillis() - failedAt
        return elapsed in 0 until COOLDOWN_MS
    }

    /** 只换 host/scheme/port，路径与 query 原样保留。 */
    private fun switchTo(request: Request, fallback: HttpUrl): HttpUrl =
        request.url.newBuilder()
            .scheme(fallback.scheme)
            .host(fallback.host)
            .port(fallback.port)
            .build()

    /**
     * 这次失败值不值得换地址再试一次。
     *
     * 用异常**类型**而不是 message 判断：OkHttp 各版本对超时的文案说法不一，
     * 按文案匹配会在升级时静默失效。
     */
    private fun shouldSwitch(request: Request, e: IOException): Boolean {
        // 读超时 = 服务端慢。换了地址大概率也慢，白等一个 timeout。
        if (e is SocketTimeoutException) return false

        // 连接阶段就失败：请求肯定还没发出去 → 任何方法都安全。
        if (e is UnknownHostException) return true   // DNS 解析不了
        if (e is ConnectException) return true       // 连不上 / 被拒
        if (e is SSLException) return true           // 证书握手失败

        // 其余（SocketException: Connection reset 等）：异常类型看不出
        // 请求有没有已经发出去，**只有幂等性能做判断依据**。
        return isIdempotent(request)
    }

    /**
     * 这个请求重发一次有没有副作用。
     *
     * GET/HEAD 显然。POST 要**逐个端点看**——不能一刀切：
     *   · 登录 / 发 token / 刷新 token：多签一个 token 而已 → 幂等
     *   · 剪藏同一链接：后端查重复用已有文章、不重复计费 → 幂等
     *   · 收藏：UNIQUE 约束保证 → 幂等
     *   · 进度上报：进度值本来就是覆盖写 → 幂等
     *   · 设备登记：upsert → 幂等
     *   · 评分 / 反馈文本：feedback 表**追加**一行，没有唯一约束 → 不幂等
     *   · 手动触发蒸馏：重复触发会跑两遍 → 不幂等
     * 所以这里用白名单，而不是「POST 一律切」或「POST 一律不切」。
     */
    private fun isIdempotent(request: Request): Boolean {
        val method = request.method
        if (method in IDEMPOTENT_METHODS) return true
        if (method != "POST") return false
        val path = request.url.encodedPath
        return IDEMPOTENT_POST_EXACT.contains(path) ||
            IDEMPOTENT_POST_PREFIXES.any { path.startsWith(it) } ||
            IDEMPOTENT_POST_SUFFIXES.any { path.endsWith(it) }
    }

    private companion object {
        const val TAG = "FallbackBaseUrl"

        /**
         * 主地址失败后的冷却期：这期间直接走备用地址。
         *
         * 60 秒是权衡：够长到「一次会话里的连续请求」都走 IP（不再逐个失败），
         * 又够短到域名恢复后很快能自动切回去。
         */
        const val COOLDOWN_MS = 60_000L

        /**
         * 重复执行不产生额外副作用的方法。
         *
         * GET/HEAD 显然。**PUT/DELETE 按 HTTP 规范就是幂等方法**：
         * 重复 PUT 同一资源 = 同样的最终状态；重复 DELETE = 第二次是 no-op。
         *
         * ⚠️ 别漏掉 PUT —— 隐私同意走的就是
         * `PUT /api/v1/users/me/consent`（后端是「取或建 + 覆盖写」，
         * 完全幂等）。漏了它的后果是：某个网络把到域名的连接 RST 掉时，
         * **App 卡在隐私同意页出不来**（登录后第一道门）。
         */
        val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS", "PUT", "DELETE")

        /** 幂等 POST 的**精确**路径。 */
        val IDEMPOTENT_POST_EXACT = setOf(
            // 剪藏：后端 CP-DUPLICATE-CLIP 先查重，重复提交同一链接
            // 复用已有文章、配额不动 → 幂等。
            // ⚠️ 必须精确匹配：/api/v1/articles/{id}/rate 与 /distill 不幂等。
            "/api/v1/articles",
            // 注销账号：重复删 → 已删 → no-op。
            "/api/v1/users/me/account/delete",
        )

        /**
         * 幂等 POST 的路径**前缀**白名单。
         *
         * · /api/v1/auth/       —— 登录（wechat-login）/ 发 token / 刷新 token。
         *   重发的后果最多是「多签一个 token / 多回访一次」：mock 登录按 code
         *   派生**确定性** user_id，同一个 code 重复登录不会建出两个用户。
         * · /api/v1/admin/auth/ —— 后台登录，同理。
         *
         * 用前缀而不是逐个端点列举：端点清单会变（前缀匹配不会漏），
         * 而认证类端点天然幂等，往这个前缀下加端点不需要改这里。
         */
        val IDEMPOTENT_POST_PREFIXES = setOf(
            "/api/v1/auth/",
            "/api/v1/admin/auth/",
        )

        /**
         * 幂等 POST 的路径**后缀**白名单。
         *
         * · /progress     —— 进度上报，是覆盖写（同一篇的同一位置写多次一样）
         * · /devices      —— 设备登记，后端是 upsert
         * · /favorites    —— 加收藏，favorites 表有 UNIQUE 约束，重复加是幂等分支
         */
        val IDEMPOTENT_POST_SUFFIXES = setOf(
            "/progress",
            "/devices",
            "/favorites",
        )
    }
}
