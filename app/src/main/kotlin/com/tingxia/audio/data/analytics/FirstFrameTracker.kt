package com.tingxia.audio.data.analytics

import android.os.SystemClock

/**
 * 首屏耗时计时器（2026-10-25）。
 *
 * ## 「首屏加载」到底量什么
 *
 * 量的是**进程启动 → 第一帧真正画出来**的墙钟时间。
 *
 * 为什么起点是**进程启动**而不是 `Activity.onCreate`：
 * 冷启动时 Application / ContentProvider / Hilt 图构建都发生在 onCreate **之前**，
 * 那段也算用户等的时间。用 onCreate 当起点会把最慢的一段排除在指标之外 ——
 * 指标好看但用户体感没变，这正是「首屏 < 1.5s」这条验收最容易被做假的地方。
 *
 * 为什么终点是**第一帧**而不是「数据加载完」：
 * 首屏指标量的是「用户看到东西」；数据是首屏之后的事（骨架屏/登录页都在第一帧就出来了）。
 *
 * ## 为什么单独一个类
 *
 * 因为它必须能**被单测**：
 * `Process.getStartUptimeMillis()` 与 `SystemClock.elapsedRealtime()` 在
 * Robolectric 里都不走真实时钟，纯逻辑抽出来才能断言
 * 「只报一次」「时长不为负」。
 */
internal class FirstFrameTracker(
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    /**
     * 进程启动时刻（毫秒，与 [SystemClock.elapsedRealtime] 同一时钟）。
     *
     * `android.os.Process.getStartUptimeMillis()`（API 24+）——
     * 注意要写全限定名：`Process` 默认解析成 `java.lang.Process`（没有这个方法）。
     */
    private val processStartMs: Long = android.os.Process.getStartUptimeMillis()

    /** 是否已经报过。首屏每个进程生命周期只该报一次。 */
    private var reported: Boolean = false

    /**
     * 第一帧画出来了 → 返回本次首屏耗时（毫秒）。
     *
     * @return 毫秒数；**已经报过则返回 null**（不再重复上报）。
     */
    fun consumeFirstFrame(): Long? {
        if (reported) return null
        reported = true
        // 理论上 now >= processStartMs；万一时钟倒退（不该发生）也别报负数。
        return (now() - processStartMs).coerceAtLeast(0L)
    }
}
