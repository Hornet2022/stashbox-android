package com.tingxia.audio.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tingxia.audio.audio.PlayerController

/**
 * 把 [PlayerController] 的 500ms 位置轮询与**界面可见性**绑起来。
 *
 * ## 为什么需要它
 * [PlayerController] 是 @Singleton，生命周期跟进程走。轮询泵原先无条件自我重投，
 * 用户把 App 切到后台、音频还在放时，8 小时能空转 57,600 次主线程唤醒 ——
 * 全花在没人看的界面上。这里在 `ON_START` 注册、`ON_STOP` 注销，
 * 界面可见时才要那个刷新率。
 *
 * ## 只停轮询，不停播放
 * 停的只是 500ms → 10s 的**降频**（后台仍在放时保留慢跳，因为完听判定和断点续听
 * 要读同一份 position 缓存；彻底停表会让上报的进度冻住）。声音、
 * MediaSession、锁屏控制都不经过这条路，见 [PlayerController] 内 positionHandler 的说明。
 *
 * 通知栏进度是另一条路：Media3 的 `DefaultMediaNotificationProvider` 直接渲染
 * MediaSession 绑定的那个 ExoPlayer，不消费这里的 position StateFlow。
 *
 * ## 计数而非布尔
 * 全屏播放器与迷你条可能同时可见，所以内部是引用计数。两个都不可见时才降频。
 *
 * ## 位置
 * 无返回值、无人机读，纯副作用。需要位置的界面自己照旧 collect 即可。
 */
@Composable
fun ObservePlayerPositionUpdates(controller: PlayerController) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.startPositionUpdates()
                Lifecycle.Event.ON_STOP -> controller.stopPositionUpdates()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        // 两个细节：
        // 1) addObserver 会把当前状态补发一遍 —— 若挂进来时已经 STARTED，
        //    ON_START 会被补发，计数正好是 1；若还停在 STOPPED，则补发的
        //    ON_CREATE 不触发任何注册，计数留在 0（正确：界面没显示就不该刷新）。
        // 2) onDispose 无条件补一次注销：此时上面的补发**不保证**成对
        //    （例如挂进来时就已 STOPPED，从没收到过 ON_START）。PlayerController
        //    侧的注销对计数 0 是 no-op，所以不会把别人还在用的计数误减。
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stopPositionUpdates()
        }
    }
}