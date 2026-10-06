package com.tingxia.audio.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import retrofit2.HttpException

/**
 * 听匣后端**业务错误信封**（接口文档 v1.2 §0.2）。
 *
 * ```json
 * { "code": 40400, "message": "Resource not found", "data": null }
 * ```
 *
 * 与 HTTP code 不同：HTTP 4xx 仅表示「请求有问题」，code 给出**业务层细分**：
 * - 4001 → 参数非法（rating 越界、reason 超 64 字符）
 * - 3001 → 配额用尽（弹付费墙）
 * - 40100 → JWT 缺失/过期（refresh-token 失败后重登录）
 *
 * **关键**：§1.4 `GET /audio-url` 在文章未 ready 时返 **404 `code 40400`**——
 * 这个 404 不是"内容不存在"，是"还没好"，客户端**应当继续轮询 §1.3**，
 * 不要当错误弹。详见 [isAudioNotReady]。
 */
@Serializable
data class ApiErrorEnvelope(
    val code: Int,
    val message: String,
    val data: kotlinx.serialization.json.JsonElement? = null,
)

/**
 * 业务异常包装：在 [HttpException] 上叠加业务 code + message。
 *
 * 用于把 4xx/5xx HttpException 转成更细粒度的判断（如 404 + code 40400 → 音频未就绪）。
 */
class ApiException(
    val httpCode: Int,
    val bizCode: Int,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    override fun toString(): String = "ApiException(http=$httpCode, code=$bizCode, msg='$message')"
}

/** §1.4 视频/音频未 ready：404 + code 40400 → "还没好，继续轮询 §1.3" */
val ApiException.isAudioNotReady: Boolean
    get() = httpCode == 404 && bizCode == 40400

/**
 * 配额用尽：弹付费墙
 *
 * CP-QUOTA-CODE：原来判 `httpCode == 429`，但后端 [stashbox QuotaExceededError]
 * 声明的是 `http_status = 403`（`backend/common/quota_service.py`），实测抛出的
 * 异常就是 `code=3001, http_status=403`。429 从来不会发生 → 这个判断恒为 false →
 * 付费墙永远不会被触发。
 *
 * 这里按 bizCode 判（3001 在全项目里唯一，配额专用），HTTP 状态码只作为兜底
 * 放宽条件，不作为必要条件——后端哪天把 403 改成 429 也不至于又静默失效。
 */
val ApiException.isQuotaExceeded: Boolean
    get() = bizCode == 3001

/**
 * 会话失效（JWT 缺失 / 过期）：需要重新登录。
 *
 * 以 **HTTP 状态码**为判据，业务码只作为**加强信号**，不是必要条件。
 *
 * ## 为什么不能再要求业务码同时是 40100
 *
 * 业务码来自响应体，而响应体经常拿不到：[parseEnvelope] 在 body 缺失或不是标准
 * 信封时给的是 `bizCode = 0`（网关 / 反代直接吐的裸 401 更是连信封都没有）。
 * 原来写成 `httpCode == 401 && bizCode == 40100`，于是对最常见的
 * 「401 + 没有业务码」恒为 false —— 一次真实的掉登录会一路掉到
 * `httpCode in 400..499` 分支，显示成「请求失败（401）」：一句废话，
 * 不会提示用户重新登录。而 `friendlyError` 里那条真正写着 401/403/407 →
 * 「登录已失效」的分支位于裸 [retrofit2.HttpException] 兜底里，只要
 * parseEnvelope 成功构造出 ApiException（几乎总是如此）就永远到不了。
 *
 * ## 业务码怎么当加强信号用
 *
 * 后端 `common/exceptions.py::_STATUS_CODE_MAP` 把所有裸 HTTP 401 统一映射成
 * `code=40100`，所以 401 + 40100 是「后端明确说的过期」；`bizCode == 0` 表示
 * 信封没拿到，按 401 处理。而 401 配上一个**别的**业务码，说明这一次 401 是针对
 * 某个具体请求的（不是整个会话没了），此时不该在客户端推断成全局登出。
 *
 * ## 403 为什么不在这���
 *
 * 这个后端大量用 403 表达**跟登录无关**的拒绝：`Forbidden`（40300）用于归属校验
 * （"not the owner of this article"）、管理员 tier 校验；配额用尽是 403 + code 3001
 * （见 [isQuotaExceeded]）；dev token 端点关闭也是 403。把它们说成「登录已失效」
 * 会把用户往错误的路上引（尤其配额：重登多少次都不会让配额回来）。
 * 407（proxy auth required）在本后端完全没用到。
 */
val ApiException.isAuthExpired: Boolean
    get() = httpCode == 401 && (bizCode == 0 || bizCode == BIZ_CODE_AUTH_EXPIRED)

/** 后端对「未认证 / token 过期」的业务码：HTTP 401 → code 40100（common/exceptions.py）。 */
private const val BIZ_CODE_AUTH_EXPIRED = 40100

/**
 * 剪藏抓取失败（后端 `map_fetcher_error`）
 *
 * 2001 = URL 不支持（HTTP 400），2002 = 抓取失败（HTTP 502/500）。
 *
 * 为什么单独判：这两条的 message 是后端**特意写成给用户看的中文**，
 * 写清了是谁的错、能不能重试、该做什么（网络不通 / 微信要���微信里打开 /
 * 文章已删除 / 对方限流…）。而 `friendlyError` 的 `httpCode in 500..599`
 * 分支会把 502 压成一句"服务暂不可用，请稍后再试" —— 那是在说**我们的服务**
 * 挂了，但实际情况是**对方网站**连不上，用户看完既不知道发生了什么，
 * 也不知道该不该重试。剪藏是听匣的第一个动作，这一句文案直接决定成败。
 */
val ApiException.isCaptureFetchFailed: Boolean
    get() = bizCode == 2001 || bizCode == 2002

/** 通用解析：从 HttpException 提取 ApiException */
private val ENVELOPE_JSON = Json { ignoreUnknownKeys = true }

/**
 * 解析错误响应体为 [ApiException]。
 *
 * 用法：
 * ```
 * try { api.xxx() }
 * catch (e: HttpException) { throw parseEnvelope(e) }
 * ```
 */
fun parseEnvelope(e: HttpException): ApiException {
    val raw: ResponseBody? = e.response()?.errorBody()
    if (raw == null) return ApiException(e.code(), 0, e.message ?: "HTTP ${e.code()}", e)
    return try {
        val text = raw.string()
        val env = ENVELOPE_JSON.decodeFromString(ApiErrorEnvelope.serializer(), text)
        ApiException(httpCode = e.code(), bizCode = env.code, message = env.message, cause = e)
    } catch (_: Exception) {
        // 响应体不是标准信封（极少数接口可能不返）
        ApiException(httpCode = e.code(), bizCode = 0, message = e.message ?: "HTTP ${e.code()}", e)
    }
}