package com.tingxia.audio.ui

import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 把网络/系统异常转成用户友好的中文文案（避免把 "HTTP 500" 之类原始错误暴露给终端用户）。
 *
 * 设计：
 * - 5xx → "服务暂不可用，请稍后再试"
 * - 401/403/407 → "登录已失效，请重新登录"
 * - 404 → "内容不存在或已删除"
 * - 4xx 其他 → "请求失败（{code}）"
 * - 超时 / 断网 → "网络不给力，请检查连接"
 * - 解析失败 → "数据格式异常"
 * - 其他 → 调用方传入的 fallback
 *
 * 使用：
 * ```
 * try { ... } catch (e: Exception) {
 *     _state.update { it.copy(error = friendlyError(e, fallback = "加载失败")) }
 * }
 * ```
 */
fun friendlyError(e: Throwable, fallback: String = "操作失败，请稍后再试"): String {
    return when (e) {
        is SocketTimeoutException -> "网络不给力，请检查连接"
        is UnknownHostException -> "网络不给力，请检查连接"
        is HttpException -> when (val code = e.code()) {
            in 500..599 -> "服务暂不可用，请稍后再试"
            401, 403, 407 -> "登录已失效，请重新登录"
            404 -> "内容不存在或已删除"
            429 -> "请求过于频繁，请稍后再试"
            in 400..499 -> "请求失败（$code）"
            else -> fallback
        }
        is IOException -> "网络不给力，请检查连接"  // 包括 ConnectException、SSL 等
        else -> fallback
    }
}