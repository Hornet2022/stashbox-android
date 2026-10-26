package com.tingxia.audio.audio

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.io.IOException

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

    /** 取 cause 链（含自己），限深防环。 */
    private fun causeChain(cause: Throwable?): List<Throwable> {
        if (cause == null) return emptyList()
        val chain = ArrayList<Throwable>(4)
        var current: Throwable? = cause
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            chain.add(current)
            current = current.cause
            depth++
        }
        return chain
    }
}
