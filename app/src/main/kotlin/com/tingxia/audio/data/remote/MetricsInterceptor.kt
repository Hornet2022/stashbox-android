package com.tingxia.audio.data.remote

import android.os.SystemClock
import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

/**
 * CP11.0.6 P2.2 响应时间埋点:
 *
 * 拦截每个 HTTP 请求,记录:
 *   - method
 *   - URL path(去掉 query string,避免长尾噪音)
 *   - HTTP status
 *   - duration_ms(从发出请求到收到响应)
 *
 * 输出到 logcat tag `HttpMetric`,便于 admin/监控侧后续解析(grep / 上报)。
 *
 * 设计权衡:
 *   - 不引入 micrometer / OpenTelemetry — 单进程 Android 用 logcat 已经够,后续接
 *     StatsD/OTel 再换实现,API 不变。
 *   - 不在 UI 线程做计算(`SystemClock.elapsedRealtime()` 是 O(1)纳秒,无压力)。
 *   - 不抛异常,失败请求也记录(便于排查 5xx)。
 */
class MetricsInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val start = SystemClock.elapsedRealtime()

        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            // 网络层异常也算耗时(用户感知到的延迟)
            val duration = SystemClock.elapsedRealtime() - start
            Log.w(
                TAG,
                "method=${request.method} path=${pathOf(request.url.encodedPath)} " +
                    "status=error duration_ms=$duration error=${e.javaClass.simpleName}",
            )
            throw e
        }

        val duration = SystemClock.elapsedRealtime() - start
        Log.i(
            TAG,
            "method=${request.method} path=${pathOf(request.url.encodedPath)} " +
                "status=${response.code} duration_ms=$duration",
        )
        return response
    }

    /**
     * 去掉尾段 ID 类长串(避免日志爆炸 + P95 分析被 outlier 拉偏)。
     * 例:`/api/v1/articles/art_906ef0bac9654c198292d7a7` → `/api/v1/articles/{id}`
     */
    private fun pathOf(raw: String): String {
        val segments = raw.split('/')
        if (segments.isEmpty()) return raw
        return segments.joinToString("/") {
            // 形如 art_xxx / dst_xxx / f-xxx 等都规整成 {id}
            if (it.length >= 20 && (it.startsWith("art_") || it.startsWith("dst_"))) {
                "{id}"
            } else {
                it
            }
        }
    }

    companion object {
        private const val TAG = "HttpMetric"
    }
}