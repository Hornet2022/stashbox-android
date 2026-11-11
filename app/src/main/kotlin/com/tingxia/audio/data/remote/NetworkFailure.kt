package com.tingxia.audio.data.remote

import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 网络失败的**稳定分类**（全 App 唯一一份）。
 *
 * ## 为什么要有它
 *
 * 失败要同时回答两个问题：**对用户说什么**、**对排查上报什么**。
 * 上报用的那个标识必须是**固定字符串**，不能用 `javaClass.simpleName` ——
 * release 包开了 R8，类名会被混淆（实测生产埋点里只剩 `"y"`，
 * 完全读不出原因；debug 包不混淆 ⇒ 这个坑在开发期看不见）。
 *
 * ## 为什么是**一份**而不是每个调用点各写一份
 *
 * 剪藏与播放都遇到"网络失败"，两处各写一份判据 ⇒ 迟早漂移
 * （一边把 404 当网络、一边不当）。所以放这里，谁用都取同一份。
 *
 * 播放链上的 media3 专有语义（IO 错误码段、解析/解码）仍由
 * [com.tingxia.audio.audio.PlaybackFailure] 负责 —— 那一层要额外认识
 * media3 的异常类型；**通用**的连接层分类则复用本文件。
 */
object NetworkFailure {

    /**
     * 这个异常的稳定标识（给埋点用，不受 R8 混淆影响）。
     *
     * 取值：`http_<code>` / `socket_timeout` / `connect_failed` /
     * `unknown_host` / `no_route` / `socket_error` / `eof` / `io_error` /
     * `other`。
     */
    fun kindOf(cause: Throwable?): String {
        if (cause == null) return "other"
        // HTTP 状态码最具体（服务端明确说了什么）——优先。
        if (cause is HttpException) return "http_${cause.code()}"
        // 连接层：按**语义**分类，而不是按类名。
        when (cause) {
            is SocketTimeoutException -> return "socket_timeout"
            is UnknownHostException -> return "unknown_host"
            is ConnectException -> return "connect_failed"
            is NoRouteToHostException -> return "no_route"
            is SocketException -> return "socket_error"
            is java.io.EOFException -> return "eof"
        }
        if (cause is IOException) return "io_error"
        return "other"
    }

    /**
     * 这个失败是**服务端明确拒绝**（4xx，客户端重试没用）吗？
     *
     * 用途：剪藏失败后要不要**回查**"是不是其实成功了"——
     * 服务端说"不行"就是真不行（不用回查）；网络层失败则**不知道**，
     * 必须回查才能对用户说真话。
     */
    fun isServerRejected(cause: Throwable?): Boolean {
        if (cause !is HttpException) return false
        val code = cause.code()
        return code in 400..499 && code != 408 && code != 429
    }
}
