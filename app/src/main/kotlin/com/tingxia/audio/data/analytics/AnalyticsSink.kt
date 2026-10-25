package com.tingxia.audio.data.analytics

/**
 * 埋点上报口。
 *
 * ## 为什么是接口而不是直接用 [EventReporter]
 *
 * 埋点的**调用方**（播放器、首屏计时）都是不该被埋点实现绑死的：
 * - 单测要断言「确实上报了 audio_play_start」，得能塞一个假 sink 进去；
 * - 播放器是 @Singleton，被 14 个单测直接 new 出来 —— 让它依赖
 *   一个需要 Retrofit 的具体类，这些测试就得连网络层一起造。
 *
 * 所以：**接口在这里，实现在 [EventReporter]**，无实现时用 [NoOpAnalyticsSink]。
 */
interface AnalyticsSink {
    /**
     * 上报一个事件。
     *
     * @param eventName 事件名，必须在 `backend/common/events.py` 的 [EventName] 枚举里，
     *   否则后端返回 400（客户端会静默丢弃）。
     * @param properties 事件上下文。值只支持 String / Number / Boolean / null ——
     *   其他类型会被转成字符串（避免上报本身抛异常）。
     */
    fun report(eventName: String, properties: Map<String, Any?> = emptyMap())
}

/**
 * 什么都不做的实现。
 *
 * 用途：① 单测里不想关心埋点；② 极端情况下（拿不到 Context）兜底。
 * 它**不是**「关掉埋点」的开关 —— 真实上报走 [EventReporter]。
 */
object NoOpAnalyticsSink : AnalyticsSink {
    override fun report(eventName: String, properties: Map<String, Any?>) = Unit
}

/**
 * 事件名常量。
 *
 * ⚠️ 必须与 `backend/common/events.py` 的 `EventName` 枚举**逐字一致** ——
 * 后端会校验（`_validate_event_name`），对不上就是 400 + 事件丢失。
 *
 * 这里只列**安卓端真的上报**的事件；服务端上报的那些（distill_* 等）
 * 由后端自己埋，客户端不需要知道。
 */
object EventNames {
    /** 首屏加载耗时（`app_first_frame`）。属性：`duration_ms`。 */
    const val APP_FIRST_FRAME = "app_first_frame"

    /** 音频启动耗时（`audio_play_start`）。属性：`duration_ms`、（可选）`article_id`。 */
    const val AUDIO_PLAY_START = "audio_play_start"
}
