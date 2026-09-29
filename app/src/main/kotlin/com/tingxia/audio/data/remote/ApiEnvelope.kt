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

/** JWT 过期：refresh-token 失败后重登录 */
val ApiException.isAuthExpired: Boolean
    get() = httpCode == 401 && bizCode == 40100

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