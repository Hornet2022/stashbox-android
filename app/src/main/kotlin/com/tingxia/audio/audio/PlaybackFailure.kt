package com.tingxia.audio.audio

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 播放失败的两类成因（2026-10-26）。
 *
 * ## 为什么必须分类
 *
 * 「播放中断」有两种，处理方式**完全相反**：
 *
 * | 成因 | 例子 | 重连有用吗 |
 * |---|---|---|
 * | 瞬时网络类 | 连接被重置 / 读超时 / 5xx / 限流 | **有用** —— 换个时间或换个地址通常就成了 |
 * | 内容类 | 音频 404 / 403 / 解码失败 / 文件被删 | **没用** —— 重试一百次也是同一个结果 |
 *
 * 此前 App 对两者一视同仁：都置成 ERROR、弹一句文案、等用户手动点重试。
 * 于是「网抖了一下」和「音频没了」变成了同一种体验 —— 而前者根本不该
 * 需要用户参与。
 *
 * ## 判定口径（不是拍脑袋）
 *
 * 判据全部来自**可观察的事实**，不是「看起来像网络问题」：
 *
 * 1. **HTTP 状态码优先**（链上有 `InvalidResponseCodeException` 时）：
 *    408 / 429 / 5xx → 瞬时；其余 4xx → 内容类。
 *    ⚠️ 顺序很重要：`InvalidResponseCodeException` 本身**也是** IOException，
 *    若先按 IOException 判，404 会被误判成「网络问题」→ 无限重连一个
 *    已经不存在的音频。
 * 2. **media3 的 IO 错误码段**（`PlaybackException.errorCode` 落在 2000~2999）
 *    → 瞬时。media3 1.4.1 已不再有 `ERROR_TYPE_*`，只剩错误码分段：
 *    2000~2999 = IO / 网络，3000~3999 = 解析，4000~4999 = 解码。
 * 3. **链上有 IOException**（`SocketException` / `SocketTimeoutException` /
 *    `EOFException` / `UnknownHostException` …）→ 瞬时。
 * 4. 其余（解码器、断言、参数错…）→ 内容类。
 *
 * 为什么要走**整条 cause 链**：`PlaybackException` 只是壳，真正的原因在
 * `cause` 里（media3 惯常做法）。只看最外层会把所有错误判成同一种。
 */
enum class PlaybackFailureKind {
    /** 瞬时网络类：值得自动重连。 */
    TRANSIENT_NETWORK,

    /** 内容类：重连没用，直接报错让用户决定。 */
    FATAL,
}

/**
 * 播放失败的分类器。
 *
 * 抽成纯对象（不碰 android.* / ExoPlayer）⇒ 能在 JVM 单测里确定性地测，
 * 不必真的去弄断一条网络连接。
 */
object PlaybackFailure {

    /**
     * media3 1.4.1 的 IO 错误码段（`ERROR_CODE_IO_*`）。
     *
     * 2000~2999 = IO / 网络类；3000 起是解析与解码 —— 那两类重连没用。
     */
    private const val IO_ERROR_CODE_MIN = 2000
    private const val IO_ERROR_CODE_MAX = 2999

    /** 走 cause 链的最大深度（防病态的环）。 */
    private const val MAX_CAUSE_DEPTH = 8

    /**
     * 这个失败是不是「瞬时网络类」。
     *
     * @param cause 播放失败的异常（通常是 `PlaybackException`，也可能是它的 cause）。
     */
    fun classify(cause: Throwable?): PlaybackFailureKind {
        val chain = causeChain(cause)
        if (chain.isEmpty()) return PlaybackFailureKind.FATAL

        // ① HTTP 状态码优先：4xx（除 408/429）说明**内容**没了，重连无用。
        chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
            ?.let { invalid ->
                return if (isRetryableStatus(invalid.responseCode)) {
                    PlaybackFailureKind.TRANSIENT_NETWORK
                } else {
                    PlaybackFailureKind.FATAL
                }
            }

        // ② media3 的 IO 错误码段。
        if (chain.any { it is PlaybackException && it.errorCode in IO_ERROR_CODE_MIN..IO_ERROR_CODE_MAX }) {
            return PlaybackFailureKind.TRANSIENT_NETWORK
        }

        // ③ 连接层异常。
        if (chain.any { it is IOException }) {
            return PlaybackFailureKind.TRANSIENT_NETWORK
        }

        // ④ 解析 / 解码 / 参数错 —— 重连一万次也是同一个结果。
        return PlaybackFailureKind.FATAL
    }

    /**
     * 这个 HTTP 状态码重试有没有意义。
     *
     * · 408 请求超时 → 服务器当时忙，等一下再来 ✓
     * · 429 限流 → 等一下再来 ✓
     * · 5xx → 服务端故障，等一下再来 ✓
     * · 其余 4xx（404 音频没了 / 403 没权限 / 400 参数错）→ 重试无意义 ✗
     */
    fun isRetryableStatus(responseCode: Int): Boolean =
        responseCode == 408 || responseCode == 429 || responseCode in 500..599

    /**
     * 把一次播放失败描述成**稳定的、可上报的**标识。
     *
     * ## 为什么不能直接上报 `javaClass.simpleName`
     *
     * release 包开了 R8（`isMinifyEnabled = true`）⇒ **类名会被混淆**。
     * 实测（2026-11-09 内测）：生产埋点里的 `cause` 是 `"y"` ——
     * 一个被混淆后的类名，**完全读不出失败原因**。
     * （debug 包不混淆，所以本地看到的是 `SocketException` 这种可读名字，
     *  于是"生产读不出来"这件事在开发期完全看不见。）
     *
     * ⇒ 埋点必须用**自己定义的固定字符串**（不受混淆影响）。
     *
     * @param cause 播放失败的异常（通常是 `PlaybackException`，也可能是它的 cause）。
     */
    fun describe(cause: Throwable?): FailureDescription {
        val chain = causeChain(cause)
        if (chain.isEmpty()) return FailureDescription("unknown")

        // ① HTTP 状态码最具体（服务端明确说了什么）——优先。
        httpStatusIn(chain)?.let { status ->
            val key = when {
                status == 404 -> "http_404"
                status == 403 -> "http_403"
                status == 408 -> "http_408"
                status == 429 -> "http_429"
                status in 500..599 -> "http_5xx"
                status in 400..499 -> "http_4xx"
                else -> "http_$status"
            }
            return FailureDescription(key, status)
        }

        // ② 连接层异常：按**语义**分类，而不是按类名（类名会被混淆）。
        chain.forEach { t ->
            val key = when (t) {
                is SocketTimeoutException -> "socket_timeout"
                is UnknownHostException -> "unknown_host"
                is ConnectException -> "connect_failed"
                is NoRouteToHostException -> "no_route"
                is SocketException -> "socket_error"
                is java.io.EOFException -> "eof"
                else -> null
            }
            if (key != null) return FailureDescription(key)
        }

        // ③ media3 的 IO 错误码段（2000~2999）——只有区间，没有更细的语义。
        if (chain.any { it is PlaybackException && it.errorCode in IO_ERROR_CODE_MIN..IO_ERROR_CODE_MAX }) {
            return FailureDescription("io_error")
        }

        // ④ 其它 IOException
        if (chain.any { it is IOException }) return FailureDescription("io_error")

        // ⑤ 解析 / 解码 / 参数错
        if (chain.any { it is PlaybackException && it.errorCode in 3000..3999 }) {
            return FailureDescription("parse_error")
        }
        if (chain.any { it is PlaybackException && it.errorCode in 4000..4999 }) {
            return FailureDescription("decode_error")
        }
        return FailureDescription("unknown")
    }

    /** 链上（含 suppressed）第一个 HTTP 状态码；没有则 null。 */
    fun httpStatusIn(chain: List<Throwable>): Int? =
        chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
            ?.responseCode

    /**
     * 取 cause 链（含自己，**含 suppressed**），限深防环。
     *
     * 对外公开：文案层（`friendlyError`）也要用同一份链 ——
     * 否则"分类说 404、文案说网络不给力"这种自相矛盾会再次出现。
     */
    fun chainFor(cause: Throwable?): List<Throwable> {
        if (cause == null) return emptyList()
        val chain = ArrayList<Throwable>(4)
        var current: Throwable? = cause
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            chain.add(current)
            current.suppressed.forEach { chain.add(it) }
            current = current.cause
            depth++
        }
        return chain
    }

    private fun causeChain(cause: Throwable?): List<Throwable> = chainFor(cause)
}

/**
 * 一次播放失败的**稳定描述**（给埋点用，不受 R8 混淆影响）。
 *
 * @param key 固定字符串（如 `http_404` / `socket_timeout` / `io_error`）
 * @param httpStatus 服务端明确给出的 HTTP 状态码（没有则 null）
 */
data class FailureDescription(
    val key: String,
    val httpStatus: Int? = null,
)
