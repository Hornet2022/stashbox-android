package com.tingxia.audio.data.analytics

import com.tingxia.audio.data.remote.EventCollectRequest
import com.tingxia.audio.data.remote.EventCollectResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 埋点上报的**线上格式**契约（2026-10-25）。
 *
 * ## 为什么锁 JSON 的键名
 *
 * 后端 `EventCollectRequest` 的字段名是 snake_case（`event_name` /
 * `device_id` / `client_ts`），而 App 端的 Retrofit `Json` 配置是
 * `Json { ignoreUnknownKeys = true }` —— **没有 namingStrategy**。
 *
 * ⇒ 客户端一旦把字段名写成驼峰，序列化出来就是 `eventName`，
 * 后端 pydantic 会判「缺必填字段」→ 400 → **事件静默丢失**。
 *
 * 这个失败模式极难察觉：App 不报错、后台不报错，只是指标一直是空的。
 * 所以这里直接把序列化结果钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EventReporterTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `请求体的键名与后端 EventCollectRequest 一致`() {
        val body = EventCollectRequest(
            eventName = "app_first_frame",
            deviceId = "and-abcdef123456",
            properties = mapOf("duration_ms" to 1234L).toJsonObject(),
            clientTs = "2026-10-25T00:00:00Z",
        )

        val encoded = json.encodeToString(EventCollectRequest.serializer(), body)
        val obj = json.parseToJsonElement(encoded).jsonObject

        assertEquals("app_first_frame", obj["event_name"]?.jsonPrimitive?.content)
        assertEquals("and-abcdef123456", obj["device_id"]?.jsonPrimitive?.content)
        assertEquals("2026-10-25T00:00:00Z", obj["client_ts"]?.jsonPrimitive?.content)
        assertEquals(1234L, obj["properties"]?.jsonObject?.get("duration_ms")?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun `请求体里不能出现驼峰键名`() {
        // 后端是 `ignoreUnknownKeys=false` 的严格模型（pydantic 默认），
        // 驼峰键会被当成「缺少必填字段」→ 400。
        val body = EventCollectRequest(eventName = "x", deviceId = "y")

        val encoded = json.encodeToString(EventCollectRequest.serializer(), body)

        assertTrue("不能出现驼峰键名：$encoded", !encoded.contains("eventName"))
        assertTrue("不能出现驼峰键名：$encoded", !encoded.contains("deviceId"))
        assertTrue("不能出现驼峰键名：$encoded", !encoded.contains("clientTs"))
    }

    @Test
    fun `properties 里的值支持字符串数字布尔与 null`() {
        val obj = mapOf(
            "duration_ms" to 12L,
            "article_id" to "art_1",
            "ok" to true,
            "missing" to null,
        ).toJsonObject()

        assertEquals(12L, obj["duration_ms"]?.jsonPrimitive?.content?.toLong())
        assertEquals("art_1", obj["article_id"]?.jsonPrimitive?.content)
        assertEquals("true", obj["ok"]?.jsonPrimitive?.content)
        assertTrue("null 也要能传（不能抛异常）", obj.containsKey("missing"))
    }

    @Test
    fun `properties 里遇到不认识的值转成字符串而不是抛异常`() {
        // 上报本身绝不能因为一个奇怪的值把调用方炸掉 ——
        // 埋点是把观测加到系统里，不是加一条新的失败路径。
        val obj = mapOf("weird" to listOf(1, 2, 3)).toJsonObject()

        assertEquals("[1, 2, 3]", obj["weird"]?.jsonPrimitive?.content)
    }

    @Test
    fun `上报失败不会把异常抛给调用方`() {
        val reporter = EventReporter(
            eventApi = object : com.tingxia.audio.data.remote.EventApi {
                override suspend fun collect(body: EventCollectRequest): EventCollectResponse =
                    throw IllegalStateException("boom")
            },
            context = RuntimeEnvironment.getApplication(),
        )

        // 关键：不抛异常。埋点失败只该写一条 warning。
        reporter.report(EventNames.APP_FIRST_FRAME, mapOf("duration_ms" to 1L))
    }
}
