package com.tingxia.audio.share

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * D9 分享结果事件总线（P1-3 回跳刷新）。
 *
 * [D9Receiver] 在回调成功后 [emit] 结果；[com.tingxia.audio.MainActivity] 的
 * [androidx.compose.runtime.LaunchedEffect] 订阅后在 UI 线程跳转文章列表并提示，
 * 实现"分享后回跳到导入结果"的闭环。
 *
 * extraBufferCapacity=1：若 AppNavigation 尚未 compose（如分享时还在登录/引导页），
 * 结果先入缓冲，待其挂载订阅时补投，避免丢失。
 */
object D9EventBus {
    private val _events = MutableSharedFlow<D9Result>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    fun emit(result: D9Result) {
        _events.tryEmit(result)
    }
}
