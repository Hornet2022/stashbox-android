package com.tingxia.audio.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dagger.hilt.android.EntryPointAccessors

/**
 * 播放通知的权限申请（2026-10-23 新增）。
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
 */
@Composable
fun RequestPlaybackNotificationPermission(
    playerController: PlayerController,
) {
    val context = LocalContext.current
    val state by playerController.state.collectAsState()
    var requested by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果不影响播放；拒绝的话只是通知栏没有控制条 */ }

    LaunchedEffect(state) {
        if (requested) return@LaunchedEffect
        if (state != PlaybackState.PLAYING) return@LaunchedEffect
        if (isPlaybackNotificationPermissionGranted(context)) return@LaunchedEffect
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        requested = true
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * 播放通知的权限是否已获得。
 *
 * 判据与 [com.tingxia.audio.ui.update.isNotificationPermissionGranted] 一致，
 * 但**不能直接复用**那个函数：它是 `ui.update` 包的（推送那一侧），
 * 而这里是播放那一侧 —— 同一个 Android 权限，两处各判一次就会漂移
 * （一处改了另一处没改）。所以规则一致但各自留一份，
 * 共同点是都用「API 33 以下按系统设置判断，以上看运行时权限」。
 */
fun isPlaybackNotificationPermissionGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return androidx.core.app.NotificationManagerCompat.from(context)
            .areNotificationsEnabled()
    }
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}
