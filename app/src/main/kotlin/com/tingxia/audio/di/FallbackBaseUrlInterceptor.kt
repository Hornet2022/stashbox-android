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
 * ## 什么时候切：按「幂等性」分，不按异常类型分
 *
 * 第一版按异常类型分（只切 UnknownHost/Connect/SSL），在真机上**漏了一类**：
 * 用户所在网络把到域名的 TLS 连接直接 RST 掉 → `SocketException:
 * Connection reset` → 没切 → 用户看到的还是「连不上」。
 * 这类错误发生时连接已经建立，异常类型看不出请求有没有发出去，
 * **只有幂等性说了算**：
 *
 *   · **幂等方法**（GET/HEAD）→ 任何失败都值得换地址再试一次
 *     （重发 GET 不会造成任何副作用）。
 *   · **有副作用的方法**（POST/PUT/PATCH/DELETE）→ 只在「连接阶段就失败」
 *     时切（DNS 解析不了、连不上、证书握手失败）—— 这三种情况下
 *     请求**肯定还没发出去**，重试是干净的。
 *     其它情况（读超时、连接被重置）请求可能已经到达服务端，
 *     换地址重试等于把同一个操作提交两遍（"删除文章"会真删两次）→ 不切。
 *     **例外**：白名单里的**幂等 POST**（见 IDEMPOTENT_POST_*）——
 *     登录、发 token、进度上报、设备登记。少了这一条，在会 RST 掉域名的
 *     网络里**连登录都做不了**（登录是 POST）→ App 直接不可用。
 *
 * **读超时一律不切**：那是服务端慢，换地址大概率也慢，切了只会让用户
 * 等两倍时间（65s × 2）却不一定拿到结果。
 *
 * ## 位置
 * 注册成 **application interceptor**（不是 network interceptor）：
 * 只有 application interceptor 能看到并改写整条请求的 URL，
 * 且它不影响鉴权头（头是按原 URL 生成的，换 host 后 Authorization
 * 照样带上 —— 网关认的是 token 不是 host）。
 */
class FallbackBaseUrlInterceptor(
    private val fallbackBaseUrl: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fallback: HttpUrl = fallbackBaseUrl.toHttpUrlOrNull()
            ?: return chain.proceed(request)

        return try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (!shouldSwitch(request, e)) throw e
            val switched = request.url.newBuilder()
                .scheme(fallback.scheme)
                .host(fallback.host)
                .port(fallback.port)
                .build()
            Log.w(
                TAG,
                "主地址失败（${e.javaClass.simpleName}: ${e.message}），切到备用地址：${request.url} → $switched",
                e,
            )
            chain.proceed(request.newBuilder().url(switched).build())
        }
    }

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
     * GET/HEAD 显然没有。POST 要**逐个端点看**——不能一刀切：
     *   · 登录 / 发 token / 刷新 token：多签一个 token 而已 → 幂等
     *   · 进度上报：进度值本来就是覆盖写 → 幂等
     *   · 设备登记：upsert → 幂等
     *   · 反馈提交、创建文章：**不幂等** → 绝不能重发
     * 所以这里用白名单，而不是「POST 一律切」或「POST 一律不切」。
     */
    private fun isIdempotent(request: Request): Boolean {
        val method = request.method
        if (method in IDEMPOTENT_METHODS) return true
        if (method !in setOf("POST", "PUT")) return false
        val path = request.url.encodedPath
        return IDEMPOTENT_POST_SUFFIXES.any { path.endsWith(it) } ||
            IDEMPOTENT_POST_PREFIXES.any { path.startsWith(it) }
    }

    private companion object {
        const val TAG = "FallbackBaseUrl"

        /** 重复执行不产生额外副作用的方法。 */
        val IDEMPOTENT_METHODS = setOf("GET", "HEAD")

        /**
         * 幂等 POST 的路径**后缀**白名单。
         *
         * · /progress —— 进度上报，是覆盖写（同一篇的同一位置写多次一样）
         * · /devices  —— 设备登记，后端是 upsert
         */
        val IDEMPOTENT_POST_SUFFIXES = setOf("/progress", "/devices")

        /**
         * 幂等 POST 的路径**前缀**白名单。
         *
         * · /api/v1/auth/       —— 登录（wechat-login）/ 发 token / 刷新 token。
         *   重发的后果最多是「多签一个 token / 多回访一次」：mock 登录按 code
         *   派生**确定性** user_id，同一个 code 重复登录不会建出两个用户。
         * · /api/v1/admin/auth/ —— 后台登录，同理。
         *
         * ⚠️ 用前缀而不是逐个端点列举：端点清单会变（前缀匹配不会漏），
         * 而认证类端点天然幂等，往这个前缀下加端点不需要改这里。
         */
        val IDEMPOTENT_POST_PREFIXES = setOf(
            "/api/v1/auth/",
            "/api/v1/admin/auth/",
        )
    }
}
