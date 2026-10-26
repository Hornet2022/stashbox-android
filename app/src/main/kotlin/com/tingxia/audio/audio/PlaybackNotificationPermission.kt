package com.tingxia.audio.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tingxia.audio.ui.update.NotificationPermissionAction
import com.tingxia.audio.ui.update.rememberNotificationPermissionState

/**
 * 播放通知的权限申请（2026-10-23 新增；2026-10-26 改用统一判据）。
 *
 * ## 为什么必须有这一步
 *
 * Android 13（API 33）起通知是**运行时权限**。没授权时：
 * 前台服务照跑、声音照出，但**通知栏里没有那条控制条** ——
 * 官方文档的原话是「声音在播但通知栏没有控制条」。
 * 对一个音频 App 来说这不是小瑕疵：用户没法在通知栏暂停、没法锁屏控制。
 *
 * 本项目此前只在「用户点了推送通知那一行」时申请（见
 * `ui/update/PushNotificationPermission.kt`），**播放通知这条路径从没申请过**。
 *
 * ## 为什么在「第一次播放」时申请，而不是一进 App
 *
 * 与推送通知的申请时机是同一个道理（那里已有说明）：一进 App 就弹
 * 「允许通知吗」，用户不知道为什么要，点「不允许」之后这个权限就永远拿不到了。
 *
 * 而「用户点了播放」是**最需要通知栏**的时刻 —— 那条控制条这时就要出现。
 * 在这个时机问，并说明用途，用户接受率最高。
 *
 * ## 为什么每个进程只问一次
 *
 * 用户拒绝过一次之后再弹系统框，系统本身就不再显示了（要到系统设置里手动开）。
 * 但我们自己不该反复触发 —— 万一用户后来在系统设置里开了，
 * 反复触发会造成无意义的弹窗。这里用进程内的一次性标记。
 *
 * ## 2026-10-26：为什么这里不再自己判断「能不能弹」
 *
 * 原来这里是 `if (isPlaybackNotificationPermissionGranted(context)) return`，
 * 也就是「只要没权限就去弹系统框」。真机实测（MIUI 14 / Android 14）证明这条路会死：
 * 用户永久拒绝之后，系统框**根本不会再弹**
 * （`GrantPermissionsActivity ... result=2` = `RESULT_PERMISSION_FIXED`），
 * 于是这里每播一次就发起一次注定失败的弹框 —— 而用户看到的是
 * 「通知栏没有控制条，App 里也没有任何说法」。
 *
 * ⇒ 现在统一走 [rememberNotificationPermissionState] 的判据：
 * 只有「还能弹系统框」这一档才弹；永久拒绝 / 系统里关了通知 ⇒ **不弹**
 * （弹了也是没反应），而是交给设置页与播放器提示条引导去系统设置。
 */
@Composable
fun RequestPlaybackNotificationPermission(
    playerController: PlayerController,
) {
    val state by playerController.state.collectAsState()
    var requested by remember { mutableStateOf(false) }

    // 判据只有一份（见 ui/update/NotificationPermissionPolicy.kt）。
    val notificationPermission = rememberNotificationPermissionState()

    LaunchedEffect(state) {
        if (requested) return@LaunchedEffect
        if (state != PlaybackState.PLAYING) return@LaunchedEffect
        // 只有「还能弹系统框」才弹。别的档位弹了也是没反应（真机实测 result=2）。
        if (notificationPermission.action != NotificationPermissionAction.REQUEST_SYSTEM_DIALOG) {
            return@LaunchedEffect
        }
        requested = true
        notificationPermission.perform()
    }
}
