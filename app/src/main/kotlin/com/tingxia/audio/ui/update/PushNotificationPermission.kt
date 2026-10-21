package com.tingxia.audio.ui.update

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Android 13（API 33）起，通知变成运行时权限。 */
private const val ANDROID_13 = 33

/**
 * 是否需要申请通知权限。
 *
 * 抽成纯函数是为了能在**单测**里覆盖这条规则（不需要 Android 运行环境）。
 */
fun needRequestNotificationPermission(sdkInt: Int, permissionGranted: Boolean): Boolean =
    sdkInt >= ANDROID_13 && !permissionGranted

/** 通知权限的当前状态 + 申请动作。 */
data class PushPermissionState(
    /** 用户已授权（API 33 以下系统默认允许，这里按"不需要申请"处理） */
    val granted: Boolean,
    /** 需要（且可以）弹系统授权框 */
    val needsRequest: Boolean,
    /** 发起申请 */
    val request: () -> Unit,
)

/**
 * 通知权限状态（可点击申请）。
 *
 * ## 为什么不在启动时申请
 *
 * Android 13 起通知是运行时权限，而且**系统只在用户真正用得上的时候才容易接受**。
 * 一进 App 就弹一个"允许通知吗"，用户不知道为什么、点"不允许"之后
 * 推送就永远推不到了（还得去系统设置里翻）。
 * 所以这里把申请动作交给"用户点了推送通知这一行"这个明确的时机，
 * 界面上用中文说明为什么要开。
 */
@Composable
fun rememberPushPermissionState(): PushPermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.isNotificationPermissionGranted()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result -> granted = result }

    val sdkInt = remember { Build.VERSION.SDK_INT }
    return PushPermissionState(
        granted = granted,
        needsRequest = needRequestNotificationPermission(sdkInt, granted),
        request = {
            // 版本守卫必须直接写 Build.VERSION.SDK_INT（不能只写局部变量 sdkInt）：
            // lint 的 NewApi 检查只认得"直接跟 SDK_INT 比较"的形式，
            // 走局部变量它判不出来，就会在 API 26 的设备上当成崩溃风险报错。
            // POST_NOTIFICATIONS 这个常量本身是 API 33 才有的。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                needRequestNotificationPermission(sdkInt, granted)
            ) {
                launcher.launch(postNotificationsPermission())
            }
        },
    )
}

/**
 * 通知权限常量（API 33 起才有）。
 *
 * 单独抽出来 + 标注 [RequiresApi]：低版本上引用它会被 lint 判成 InlinedApi
 * （常量会被内联进调用方的字节码）。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun postNotificationsPermission(): String = Manifest.permission.POST_NOTIFICATIONS

/** 当前是否已获得通知权限。 */
fun Context.isNotificationPermissionGranted(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        // 13 以下没有运行时通知权限：能不能收到通知只取决于用户的系统设置。
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }
    return ContextCompat.checkSelfPermission(this, postNotificationsPermission()) ==
        PackageManager.PERMISSION_GRANTED
}
