package com.tingxia.audio.data.analytics

import android.content.Context
import android.util.Log
import com.tingxia.audio.data.remote.EventApi
import com.tingxia.audio.data.remote.EventCollectRequest
import com.tingxia.audio.util.analyticsDeviceId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 客户端埋点上报器（2026-10-25）。
 *
 * ## 三条硬规矩
 *
 * 1. **绝不影响主流程**：上报是 fire-and-forget，失败只写一条 warning。
 *    埋点是把「观测」加到系统里，不是加一条新的失败路径 —— 上报挂了，
 *    播放/登录/首屏都必须照常工作。
 * 2. **device_id 必须稳定**：后端限流按它算（每设备 100 events/min，
 *    `backend/common/event_collect.py:16`）。每次随机 ⇒ 限流桶无限增长、
 *    同一用户的上报散到不同桶里，按设备的分析全乱。取值见
 *    [com.tingxia.audio.util.analyticsDeviceId]。
 * 3. **不在主线程发请求**：走 [scope]（IO 调度器）。
 *
 * ## 为什么用 `SupervisorJob`
 *
 * 一个上报失败不该把整个 scope 拆掉 —— 否则第一条失败后，
 * 后面所有埋点都不会再发（而且是静默的：没人知道 scope 已经死了）。
 */
@Singleton
class EventReporter @Inject constructor(
    private val eventApi: EventApi,
    @ApplicationContext private val context: Context,
) : AnalyticsSink {

    /**
     * 上报用的独立 scope。
     *
     * 不用调用方的 scope：调用方往往是 ViewModel 的 scope（页面一销毁就取消），
     * 那样「用户点了播放就立刻退出页面」这种最常见的场景反而上报不出去。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 埋点用的稳定 device_id。
     *
     * `by lazy` —— 首次上报时才去算 ANDROID_ID 并落盘，
     * 避免每个冷启动都做一次无谓的 SharedPreferences 写。
     */
    private val deviceId: String by lazy { analyticsDeviceId(context) }

    override fun report(eventName: String, properties: Map<String, Any?>) {
        scope.launch {
            runCatching {
                eventApi.collect(
                    EventCollectRequest(
                        eventName = eventName,
                        deviceId = deviceId,
                        properties = properties.toJsonObject(),
                        clientTs = Instant.now().toString(),
                    ),
                )
            }.onSuccess { response ->
                // 后端写库失败时返回 ok=false（不是 5xx）—— 必须能看出来。
                if (!response.ok) {
                    Log.w(TAG, "埋点未被后端落库 event=$eventName")
                }
            }.onFailure { error ->
                Log.w(TAG, "埋点上报失败 event=$eventName: ${error.message}")
            }
        }
    }

    companion object {
        private const val TAG = "EventReporter"
    }
}

/**
 * 把 `Map<String, Any?>` 转成后端能收的 JSON 对象。
 *
 * 只支持 String / Number / Boolean / null —— 后端 `properties: Dict[str, Any]`
 * 收任意 JSON 值，但**上报本身绝不能因为一个奇怪的值抛异常**
 * （那就变成「因为埋点导致崩溃」）。所以其他类型一律转字符串。
 */
internal fun Map<String, Any?>.toJsonObject(): JsonObject =
    JsonObject(
        mapValues { (_, value) ->
            when (value) {
                null -> JsonNull
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is String -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        },
    )
