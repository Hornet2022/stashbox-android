package com.tingxia.audio.ui

import com.tingxia.audio.data.remote.ApiException
import com.tingxia.audio.data.remote.isAudioNotReady
import com.tingxia.audio.data.remote.isAuthExpired
import com.tingxia.audio.data.remote.isCaptureFetchFailed
import com.tingxia.audio.data.remote.isQuotaExceeded
import com.tingxia.audio.data.remote.parseEnvelope
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 把网络/系统异常转成用户友好的中文文案（避免把 "HTTP 500" 之类原始错误暴露给终端用户）。
 *
 * 设计：
 * - ApiException（业务信封解析后）→ 按业务码优先分流
 *   - 音频未 ready（404 + 40400）→ "音频还在蒸馏中，请稍候"
 *   - 配额用尽（403 + 3001）→ "今日配额已用完，开通会员继续收听"
 *   - 会话失效（401，业务码 40100 或缺失）→ [AUTH_EXPIRED_MESSAGE]
 *   - 剪藏抓取失败（2001/2002）→ **原样用后端文案**（网络不通/微信风控/已删除/限流）
 *   - 5xx → "服务暂不可用，请稍后再试"
 *   - 404 → "内容不存在或已删除"
 *   - 429 → "请求过于频繁，请稍后再试"
 *   - 4xx 其他 → "请求失败（{code}）"
 * - 5xx → "服务暂不可用，请稍后再试"
 * - 401 → [AUTH_EXPIRED_MESSAGE]
 * - 404 → "内容不存在或已删除"
 * - 4xx 其他 → "请求失败（{code}）"
 * - 超时 / 断网 → "网络不给力，请检查连接"
 * - 解析失败 → "数据格式异常"
 * - 其他 → 调用方传入的 fallback
 *
 * ## 关于 403 / 407（这里曾经写错成 401/403/407）
 *
 * 旧 KDoc 承诺「401/403/407 → 登录已失效」，但那条规则只存在于下面的裸
 * [retrofit2.HttpException] 兜底分支里，而 [parseEnvelope] 几乎总是能构造出
 * ApiException，所以那条分支实际上到不了 —— 文档描述的行为根本不存在。
 *
 * 现在两处都只认 401，因为这个后端的 403 跟登录无关：
 * `Forbidden`（40300）用于归属校验（"not the owner of this article"）和管理员
 * tier 校验，配额用尽是 403 + 3001（dev token 端点关闭也是 403）。把这些报成
 * 「登录已失效」会把用户引到错的地方 —— 比如配额用尽时重登多少次都不会恢复，
 * 用户只会反复登录、反复失败。407（proxy auth required）本后端完全没用到。
 *
 * 403 的具体含义由业务码区分（3001 → 付费墙，40300 → 归属/权限问题），
 * 都不该在这里被压成「登录已失效」。
 *
 * 使用：
 * ```
 * try { ... } catch (e: Exception) {
 *     _state.update { it.copy(error = friendlyError(e, fallback = "加载失败")) }
 * }
 * ```
 */
fun friendlyError(e: Throwable, fallback: String = "操作失败，请稍后再试"): String {
    // 1) 业务信封异常优先：HttpException 先尝试解析为 ApiException
    val apiEx: ApiException? = when (e) {
        is ApiException -> e
        is HttpException -> runCatching { parseEnvelope(e) }.getOrNull()
        else -> null
    }
    if (apiEx != null) {
        return when {
            apiEx.isAudioNotReady -> "音频还在蒸馏中，请稍候"
            apiEx.isQuotaExceeded -> "今日配额已用完，开通会员继续收听"
            apiEx.isAuthExpired -> AUTH_EXPIRED_MESSAGE
            // 剪藏抓取失败：**用后端给的中文原文**，别压成"服务暂不可用"。
            // 后端知道到底是网络不通 / 微信风控 / 文章已删 / 对方限流，
            // 每种给的下一步都不一样，客户端没资格替它概括成一句废话。
            apiEx.isCaptureFetchFailed -> apiEx.message.ifBlank { "抓取失败，请稍后重试" }
            apiEx.httpCode in 500..599 -> "服务暂不可用，请稍后再试"
            apiEx.httpCode == 404 -> "内容不存在或已删除"
            apiEx.httpCode == 429 -> "请求过于频繁，请稍后再试"
            apiEx.httpCode in 400..499 -> "请求失败（${apiEx.httpCode}）"
            else -> apiEx.message.ifBlank { fallback }
        }
    }

    // 2) 兜底按异常类型分流（只有 parseEnvelope 自己失败才会走到这里）
    return when (e) {
        is SocketTimeoutException -> "网络不给力，请检查连接"
        is UnknownHostException -> "网络不给力，请检查连接"
        is HttpException -> when (val code = e.code()) {
            in 500..599 -> "服务暂不可用，请稍后再试"
            401 -> AUTH_EXPIRED_MESSAGE
            404 -> "内容不存在或已删除"
            429 -> "请求过于频繁，请稍后再试"
            in 400..499 -> "请求失败（$code）"
            else -> fallback
        }
        is IOException -> "网络不给力，请检查连接"  // 包括 ConnectException、SSL 等
        else -> fallback
    }
}

/**
 * 「登录已失效」的唯一文案。
 *
 * 拦截器判出会话过期时（[com.tingxia.audio.auth.SessionExpirySignal] 的消费方）
 * 和 [friendlyError] 判出 401 时都用它，避免同一个事实在两个地方写出两句话、
 * 慢慢漂移成不一致的提示。
 */
const val AUTH_EXPIRED_MESSAGE = "登录已失效，请重新登录"
