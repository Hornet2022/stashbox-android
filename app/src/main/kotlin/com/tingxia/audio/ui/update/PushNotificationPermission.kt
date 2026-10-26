package com.tingxia.audio.ui.update

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.core.content.ContextCompat

/**
 * 通知权限状态 + 该做什么动作（2026-10-26 新增）。
 *
 * 这是 [notificationPermissionAction] 的 Compose 侧外壳：
 * 判据只有一份（纯函数），这里只负责**取事实**与**执行动作**。
 */
data class NotificationPermissionState(
    /** 运行期权限已授予（API 33 以下恒为 true）。 */
    val granted: Boolean,
    /** 系统层面本 App 的通知总开关是开的。 */
    val notificationsEnabled: Boolean,
    /** 现在该做什么。 */
    val action: NotificationPermissionAction,
    /** 执行 [action]。已开启时什么都不做。 */
    val perform: () -> Unit,
)

/**
 * 「我们是否已经向用户弹过通知授权框」的记账。
 *
 * ## 为什么必须自己记
 *
 * Android 没有公开 API 能直接问「这个权限是不是已经被永久拒绝了」。
 * 只能问两个间接事实：
 * - `shouldShowRequestPermissionRationale()`：false = 没问过 **或** 永久拒绝；
 * - 系统框有没有真的弹出来：只有弹出来才有回调。
 *
 * 而**永久拒绝之后系统框根本不会再弹** ⇒ 回调永远不来 ⇒
 * 只靠回调就永远分不清「用户拒绝了」和「系统不弹了」。
 * ⇒ 只能自己记「我弹过没有」。
 *
 * ## 什么时候记
 *
 * **在发起弹框之前**记，不是等回调。
 * 因为永久拒绝的情况下回调永远不会来 —— 等不到回调就永远记不上，
 * 于是每次都以为「还没问过」，每次都去弹一个系统不会显示的框（= 死路）。
 *
 * 代价：如果那次弹框其实**没弹出来**（比如 Activity 还没 resumed），
 * 我们会误以为「问过了」。这个代价是可接受的 ——
 * 误判的后果只是跳去系统设置页，而系统设置页里的开关**一定在**，
 * 用户照样能开上。反过来（明明永久拒绝了却还去弹框）才是真正的死路。
 */
internal object NotificationPermissionAskedStore {

    private const val PREFS_NAME = "notification_permission"
    private const val KEY_ASKED_POST_NOTIFICATIONS = "asked_post_notifications"

    /** 是否已经问过用户。 */
    fun wasAsked(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED_POST_NOTIFICATIONS, false)

    /** 记下「已经问过」。 */
    fun markAsked(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ASKED_POST_NOTIFICATIONS, true)
            .apply()
    }
}

/**
 * 跳到「本 App 的通知设置」系统页。
 *
 * ## 为什么必须真的有这个出口
 *
 * 这是「用户永久拒绝」这一档**唯一**能生效的动作（系统框已经不会出现了）。
 * 没有它 ⇒ 用户点了设置页那一行毫无反应 ⇒ 只能来反馈「通知栏没有了」。
 *
 * ## 为什么用 ACTION_APP_NOTIFICATION_SETTINGS 而不是 ACTION_APPLICATION_DETAILS_SETTINGS
 *
 * 前者直接落到「听匣 → 通知」这一页（开关就在第一屏）；
 * 后者是应用详情页，通知开关还要再点两层「其他权限」才找得到。
 * 用户已经因为「找不到开关」卡过一次了，别再让他找第二次。
 */
fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

/**
 * 取当前的 Activity（`shouldShowRequestPermissionRationale` 只能在 Activity 上问）。
 *
 * Compose 里 `LocalContext.current` 拿到的通常是 Activity，
 * 但也可能是 ContextWrapper（ComposeView 嵌在别的上下文里时会）⇒ 要往下剥。
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 通知权限状态（可执行动作）。
 *
 * ## 与 [rememberPushPermissionState] 的关系
 *
 * [rememberPushPermissionState] 是老接口（只管「推送通知」那一行）。
 * 本函数是**全 App 唯一**的通知权限入口 —— 推送通知与播放通知（通知栏控制条）
 * 用的是**同一个权限**，两边必须给出一致的状态与一致的动作，
 * 否则就会出现「推送那行说已开启、播放那行说没开」这种自相矛盾。
 */
@Composable
fun rememberNotificationPermissionState(): NotificationPermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.isNotificationPermissionGranted()) }
    // 系统层面的总开关（MIUI 里单独关掉某个 App 的通知时，运行期权限仍是 granted，
    // 只看 granted 会得出「已经能发通知」的错误结论）。
    var notificationsEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    // ⚠️ 这两项**必须在弹框结果回来之后重新读**，不能 `remember` 住（2026-10-26 修）。
    //
    // 踩过的坑：两者一开始都用 `remember { ... }` 取了一次值。
    // 而它们**恰恰是被弹框这件事改变的**：
    // · askedBefore：弹框发起前就记上了「问过」；
    // · shouldShowRationale：用户拒绝一次后，系统才从 false 变成 true
    //   （true = 还能再问一次；false = 永久拒绝，系统不再问）。
    //
    // 记成陈旧值的后果：用户第一次拒绝之后，App 手里握着
    // 「没问过 + 不许再问」⇒ 判定成「永久拒绝」⇒ 直接跳系统设置。
    // 看着不算错（系统设置确实能开上），但用户明明还能正常点一次授权框，
    // 我们却把他支到了系统设置 —— **能用一步解决的事，非要两步**。
    var askedBefore by remember {
        mutableStateOf(NotificationPermissionAskedStore.wasAsked(context))
    }
    var shouldShowRationale by remember {
        mutableStateOf(readShouldShowRationale(context))
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        granted = result
        notificationsEnabled =
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        // 弹框有结果了 ⇒ 事实变了，重新读一遍（见上面那三条注释）。
        askedBefore = NotificationPermissionAskedStore.wasAsked(context)
        shouldShowRationale = readShouldShowRationale(context)

        // ⚠️ 兜底（2026-10-26 加）：系统**拒绝再弹**授权框时，
        // 用户点了「开启」却什么都没发生 —— 那就是这次要修的那条死路。
        //
        // 怎么发现的「系统拒绝了」？弹框的回调**照样会来**，
        // 只是结果是「未授权」，而且系统那边 `shouldShowRequestPermissionRationale`
        // 仍然是 false（= 永久拒绝，不再问）。
        //
        // 为什么必须有这个兜底：判据里那个「我们问过没有」是**我们自己记**的，
        // 而它只存在于新版 App 的 SharedPreferences 里。
        // ⇒ **从旧版本升级上来的用户**（比如 0.4.2 → 0.4.3）这个标记是不存在的，
        // 判据会说「还没问过」⇒ 去弹框 ⇒ 系统不弹 ⇒ 又变成死路。
        // 有了这个兜底，第一次点就能把人直接送到系统设置（那一档**一定**能生效）。
        if (shouldFallbackToSystemSettings(result, shouldShowRationale)) {
            openAppNotificationSettings(context)
        }
    }

    val sdkInt = remember { Build.VERSION.SDK_INT }

    // ⚠️ 回到前台时**必须重新读一遍事实**（2026-10-26 修，真机实测发现的）。
    //
    // 用户路径就是这样的：点「播放通知」→ 跳到系统通知设置页 → 打开开关 →
    // 回到 App。**如果不刷新，App 仍然写着「通知已关闭」** ——
    // 用户明明刚打开，App 却在说它没开（实测截图确认过）。
    // 那等于「App 不说实话」，正是这次要消灭的那类问题。
    //
    // 为什么用 **Activity** 的生命周期而不是 `LocalLifecycleOwner`：
    // 后者在 Navigation Compose 里被覆盖成 NavBackStackEntry，
    // **entry 的身份会漂移** ⇒ effect 被拆掉重建 ⇒ 观察者可能只注册不触发
    // （同一个坑在 `ui/components/ObservePlayerPositionUpdates.kt` 里已经踩过一次，
    // 那里有完整的说明）。Activity 实例在整个 App 生命周期里唯一且稳定。
    val activity = context.findActivity()
    DisposableEffect(activity) {
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = context.isNotificationPermissionGranted()
                notificationsEnabled =
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
                askedBefore = NotificationPermissionAskedStore.wasAsked(context)
                shouldShowRationale = readShouldShowRationale(context)
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    val action = notificationPermissionAction(
        NotificationPermissionFacts(
            sdkInt = sdkInt,
            granted = granted,
            notificationsEnabled = notificationsEnabled,
            askedBefore = askedBefore,
            shouldShowRationale = shouldShowRationale,
        ),
    )

    return NotificationPermissionState(
        granted = granted,
        notificationsEnabled = notificationsEnabled,
        action = action,
        perform = {
            performNotificationPermissionAction(
                action = action,
                context = context,
                launchRequest = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
        },
    )
}

/**
 * 读「系统还允不允许再弹一次授权框」。
 *
 * 拿不到 Activity 时按 false 处理（= 系统不会再弹）⇒ 动作退到「去系统设置」，
 * 那是**一定能生效**的那一档，不会变成死路。
 */
private fun readShouldShowRationale(context: Context): Boolean =
    context.findActivity()?.shouldShowRequestPermissionRationale(
        Manifest.permission.POST_NOTIFICATIONS
    ) ?: false

/**
 * 弹框有结果之后，要不要**立刻**把用户送到系统通知设置页。
 *
 * ## 判据
 *
 * 「未授权」且「系统说不会再问」⇒ 系统已经拒绝了 ⇒ 再点也弹不出来
 * ⇒ 唯一还有效的动作是去系统设置。
 *
 * ## 为什么要单独抽出来
 *
 * 因为这是**升级路径**上的关键一环：
 * 判据里的「我们问过没有」是 App 自己记的（新版才有那个 SharedPreferences 标记），
 * 所以**从旧版本升级上来的用户**第一次点时，判据会说「还没问过」⇒ 去弹框
 * ⇒ 而系统其实早就不弹了 ⇒ 用户点了没反应。
 *
 * 有了这个兜底：**第一次点**就能把人送到系统设置 —— 而系统设置里那个开关**一定在**。
 *
 * @param granted 弹框的回调结果（是否授权）
 * @param shouldShowRationale 系统现在的说法：还允不允许再弹一次
 */
internal fun shouldFallbackToSystemSettings(
    granted: Boolean,
    shouldShowRationale: Boolean,
): Boolean = !granted && !shouldShowRationale

/**
 * 执行一个通知权限动作（2026-10-26 新增）。
 *
 * ## 为什么要抽成顶层函数
 *
 * 因为**「点了到底做了什么」才是这次修的那件事**。
 *
 * 判据（[notificationPermissionAction]）只回答「该做什么」，
 * 而真机上那次死路发生在**执行**这一层：不管判据说该做什么，
 * 旧代码一律去弹系统框 —— 永久拒绝之后那个框根本不会出现。
 *
 * 所以判据与执行**必须一起被测**，否则就会出现
 * 「判据写对了、但接线还是老样子」的假绿。
 * Composable 里的 lambda 不好直接测 ⇒ 抽成顶层函数，
 * 把「发起系统框」作为参数传进来，于是可以断言：
 * **永久拒绝时，弹框那个回调绝不能被调用**。
 *
 * @param launchRequest 发起系统授权框（由调用方提供，因为它需要 ActivityResultLauncher）
 */
internal fun performNotificationPermissionAction(
    action: NotificationPermissionAction,
    context: Context,
    launchRequest: () -> Unit,
) {
    when (action) {
        NotificationPermissionAction.ALREADY_ON -> Unit

        NotificationPermissionAction.REQUEST_SYSTEM_DIALOG -> {
            // 记账必须在**发起之前**（见 NotificationPermissionAskedStore 的说明）。
            NotificationPermissionAskedStore.markAsked(context)
            launchRequest()
        }

        NotificationPermissionAction.OPEN_SYSTEM_SETTINGS -> {
            // 系统框已经不会出现了 —— 唯一还能生效的动作是跳系统设置。
            openAppNotificationSettings(context)
        }
    }
}

/**
 * 是否需要申请通知权限。
 *
 * ⚠️ 这是 [notificationPermissionAction] 的**薄封装**，不是另一份判据。
 * 保留这个老签名是因为仓里已有单测锁着它的语义
 * （见 `PushPermissionPolicyTest`：13 以下不弹、已授权不重复弹）。
 */
fun needRequestNotificationPermission(
    sdkInt: Int,
    permissionGranted: Boolean,
    notificationsEnabled: Boolean = permissionGranted,
    askedBefore: Boolean = false,
    shouldShowRationale: Boolean = false,
): Boolean =
    notificationPermissionAction(
        NotificationPermissionFacts(
            sdkInt = sdkInt,
            granted = permissionGranted,
            notificationsEnabled = notificationsEnabled,
            askedBefore = askedBefore,
            shouldShowRationale = shouldShowRationale,
        ),
    ) == NotificationPermissionAction.REQUEST_SYSTEM_DIALOG

/*
 * 2026-10-26 删除了老的 `PushPermissionState` 与 `rememberPushPermissionState()`。
 *
 * 它们只会「弹系统框」，而用户永久拒绝之后系统框**弹不出来**
 * （真机实测 `GrantPermissionsActivity ... result=2`）⇒ 用户点了没反应。
 * 新的 `rememberNotificationPermissionState()` 多了 OPEN_SYSTEM_SETTINGS 那一档，
 * 判据也只有一份（见 NotificationPermissionPolicy.kt）。
 *
 * 留着两个并行的接口 = 迟早有一处忘了改 ⇒ 宁可删掉。
 */

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
