package com.tingxia.audio.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 客户端事件上报（对应后端 `backend/common/event_collect.py` 的
 * `POST /api/v1/events/collect`）。
 *
 * ## 为什么请求体里**没有** article_id 顶层字段
 *
 * 后端 `EventCollectRequest.article_id` 的类型是 `Optional[int]`
 * （见 `backend/common/event_collect.py:46`），而本项目的文章 id 是
 * `art_<24位十六进制>` **字符串**（`backend/content-service/main.py:225`
 * `return f"art_{uuid.uuid4().hex[:24]}"`）。
 *
 * ⇒ 把 `art_xxx` 放进顶层 `article_id` 会被 pydantic 判成类型不匹配 → **422**，
 * 整条埋点丢失（而且是静默的：客户端只看到 422，事件就没了）。
 *
 * 所以文章 id 一律放 `properties`（JSONB，无类型约束）。
 * 这是后端 schema 的历史遗留（它自己的 docstring 也记着这个冲突），
 * 修后端要改 schema + 迁移，属另一个议题。
 */
@Serializable
data class EventCollectRequest(
    @SerialName("event_name") val eventName: String,
    @SerialName("device_id") val deviceId: String,
    /** 事件上下文。后端会把 device_id / client_ts 也塞进这里一起落库。 */
    @SerialName("properties") val properties: JsonObject = JsonObject(emptyMap()),
    /** 客户端时刻（ISO-8601）。后端 `Optional[datetime]`。 */
    @SerialName("client_ts") val clientTs: String? = null,
)

/**
 * 上报结果。
 *
 * ⚠️ 后端在埋点写库失败时返回 `{"ok": false, "event_id": null}`（**不是** 5xx）——
 * 见 `backend/common/event_collect.py:150-152`「不许谎报 ok=True」。
 * 所以 `ok=false` 是一等公民，必须能被读出来，不能当成功。
 */
@Serializable
data class EventCollectResponse(
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("event_id") val eventId: Long? = null,
)

interface EventApi {
    @POST("api/v1/events/collect")
    suspend fun collect(@Body body: EventCollectRequest): EventCollectResponse
}
