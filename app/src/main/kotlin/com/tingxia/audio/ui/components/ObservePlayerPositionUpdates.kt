package com.tingxia.audio.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
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
 * ## 为什么用 **Activity** 的生命周期，而不是 `LocalLifecycleOwner`
 *
 * 2026-10-23 真机实测发现的坑：进度条**每 10 秒才跳一下**（而不是 500ms），
 * 字幕也跟着 10 秒才更新一次 —— 症状极有欺骗性，声音照放、时长照算，
 * 看起来像"产品把刷新率调慢了"，真实原因是**观察者压根没注册**。
 *
 * 根因：`LocalLifecycleOwner` 在 Navigation Compose 里被覆盖成
 * **NavBackStackEntry**（不是 Activity）。而 `DisposableEffect(lifecycleOwner, ...)`
 * 的 key 就是它 —— **entry 的身份会漂移**（转场、恢复、跨 entry 重组），
 * 于是 effect 被拆掉重建。重建时若新的 owner 还没 STARTED，
 * `addObserver` 不会补发 ON_START → **只有「注销」没有配对的「注册」** →
 * `positionObserverCount` 被偷走 → 掉回 10s 档。
 *
 * 而且旧代码里那句推理是错的：
 * 「PlayerController 侧的注销对计数 0 是 no-op，所以不会把别人还在用的计数误减」——
 * 只有当**当前**计数已经是 0 时才是 no-op。别人还持有 1 时，
 * 这一减就把别人的那份偷走了。
 *
 * 改用 Activity 的生命周期，两个问题一起消失：
 * - Activity 实例在整个 App 生命周期里**唯一且稳定** → effect 不会因为 key 漂移而重建；
 * - Activity 的 STARTED/STOPPED 恰好就是「App 在前台/在后台」——
 *   这本来就是降频决策要的语义（而不是「某个界面在不在栈顶」）。
 *
 * ## 计数而非布尔
 * 全屏播放器与迷你条可能同时可见，所以内部是引用计数。两个都不可见时才降频。
 *
 * ## 位置
 * 无返回值、无人机读，纯副作用。需要位置的界面自己照旧 collect 即可。
 */
@Composable
fun ObservePlayerPositionUpdates(controller: PlayerController) {
    val activity = LocalContext.current.findActivity()
    if (activity == null) {
        // 拿不到 Activity（理论上不该发生）→ 退化成「不管可见性」，
        // 让 PlayerController 按「在不在播」决定刷新率。
        DisposableEffect(controller) {
            controller.startPositionUpdates()
            onDispose { controller.stopPositionUpdates() }
        }
        return
    }
    DisposableEffect(activity, controller) {
        // registered 记录**这个观察者自己**有没有加过计数 ——
        // 只有加过才该减。旧代码在 onDispose 里无条件减，
        // 会在「从没收到过 ON_START」时偷走别人的计数。
        var registered = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (!registered) {
                        registered = true
                        controller.startPositionUpdates()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    if (registered) {
                        registered = false
                        controller.stopPositionUpdates()
                    }
                }
                else -> Unit
            }
        }
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        lifecycle?.addObserver(observer)

        // addObserver 会把当前状态补发一遍：挂进来时 Activity 已经 STARTED，
        // ON_START 会被补发 → 计数正好 1；还停在 STOPPED 则不注册（正确：
        // App 在后台，界面没显示就不该要 500ms）。
        onDispose {
            lifecycle?.removeObserver(observer)
            // 只减自己加过的那一次。
            if (registered) controller.stopPositionUpdates()
        }
    }
}

/**
 * 从 [Context] 里剥出 [Activity]。
 *
 * Compose 里 `LocalContext.current` 通常就是 Activity，但有时被包成
 * [ContextWrapper]（主题包装、Dialog 上下文）。逐层剥到 Activity 为止。
 */
internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return ctx as? Activity
}
