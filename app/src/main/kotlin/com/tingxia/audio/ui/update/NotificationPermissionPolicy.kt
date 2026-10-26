package com.tingxia.audio.ui.update

/**
 * 通知权限的**可行动状态**（2026-10-26 新增）。
 *
 * ## 为什么要有这个枚举
 *
 * 原来的判据只有一个布尔量：「有没有运行期权限」。
 * `needRequestNotificationPermission(sdkInt, permissionGranted)` 返回 true 就去弹系统框。
 *
 * 实测（MIUI 14 / Android 14 真机，0.4.2 包）证明这条判据是**不完整的**：
 * 用户把 POST_NOTIFICATIONS **永久拒绝**之后，再点设置页那一行，
 * logcat 里是
 *
 *     V GrantPermissionsActivity: Permission grant result ... permission=android.permission.POST_NOTIFICATIONS
 *       isImplicit=false result=2
 *
 * `result=2` = `RESULT_PERMISSION_FIXED` —— **系统拒绝再弹授权框**。
 * 于是：屏幕上一个弹窗都没有，副标题还写着「点击开启」，用户点了**什么都没发生**。
 * （反向对照：把 `pm set-permission-flags ... user-fixed` 去掉之后，同一个点击立刻拉起了
 * `GrantPermissionsActivity` ⇒ 死路的原因就是这个标志位，不是 onClick 写错了。）
 *
 * ⇒ 「有没有权限」这个布尔量答不出「**现在该做什么**」。
 * 三种情况的正确动作完全不同，必须分开：
 *
 * | 情况 | 正确动作 |
 * |---|---|
 * | 已经能发通知 | 什么都不用做 |
 * | 还没问过 / 用户上次拒绝了但系统还允许再弹 | 弹系统授权框 |
 * | 用户永久拒绝过，或在系统设置里把本 App 通知关了 | **只能去系统设置**（系统框已经不会出现了） |
 *
 * ## 为什么把「系统设置」这一档显式写出来
 *
 * 因为它是**唯一**在所有坏情况下都有用的动作：系统设置页里那个开关一定在。
 * 只要有任何一个状态落不到任何动作上，用户就会遇到「点了没反应」——
 * 而「点了没反应」是这个 App 已经被记过两次的坑（见 SettingsRow 的注释）。
 */
enum class NotificationPermissionAction {
    /** 已经能发通知：不需要任何动作。 */
    ALREADY_ON,

    /** 还能弹系统授权框：让系统自己问用户。 */
    REQUEST_SYSTEM_DIALOG,

    /**
     * 系统不会再弹授权框（或通知在系统层面被关了）：**只能跳系统设置**。
     *
     * 这一档是本文件存在的核心理由：它把「用户的操作没生效」变成
     * 「用户有一件一定能生效的事可做」。
     */
    OPEN_SYSTEM_SETTINGS,
}

/**
 * 判定通知权限动作所需的全部事实。
 *
 * ⚠️ 每一项都是**独立**的事实，不能互相推导：
 * - [granted] 是运行期权限（API 33+ 才有）；
 * - [notificationsEnabled] 是**系统层面**本 App 的通知总开关（13 以下就是它说了算）；
 * - [askedBefore] 是**我们**记下的「已经问过用户没有」（自己记，不猜）；
 * - [shouldShowRationale] 是系统的说法「还能不能再弹一次」。
 *
 * 真实设备上「已授予但系统里关了通知」是常态（MIUI 会单独关某个 App 的通知），
 * 而 `granted` 在那时仍然是 true —— 只看 [granted] 会得出「已经能发通知」的**错误结论**。
 */
data class NotificationPermissionFacts(
    /** `Build.VERSION.SDK_INT`。 */
    val sdkInt: Int,
    /** 运行期权限是否已授予（API 33 以下恒为 true —— 那里没有运行时权限）。 */
    val granted: Boolean,
    /** 系统层面本 App 的通知是否被打开（`NotificationManagerCompat.areNotificationsEnabled()`）。 */
    val notificationsEnabled: Boolean,
    /** 我们此前是否已经向用户弹过通知授权框（自己记在 SharedPreferences 里）。 */
    val askedBefore: Boolean,
    /** 系统是否还允许再弹一次授权框（`Activity.shouldShowRequestPermissionRationale()`）。 */
    val shouldShowRationale: Boolean,
)

/** Android 13（API 33）起通知是运行时权限。 */
internal const val ANDROID_13 = 33

/**
 * 通知权限 → 现在该做什么。**全 App 唯一一份判据。**
 *
 * 纯函数（不碰 Android），所以能在 JVM 单测里把每一种组合都锁住 ——
 * 而这些组合在真机上要么造起来很麻烦（永久拒绝），要么造出来就没法复现。
 */
fun notificationPermissionAction(facts: NotificationPermissionFacts): NotificationPermissionAction {
    // 已经能发通知（权限有 + 系统没关）→ 什么都不用做。
    if (facts.granted && facts.notificationsEnabled) {
        return NotificationPermissionAction.ALREADY_ON
    }

    // Android 13+ 且运行期权限没拿到 —— 这才是「可以弹系统框」的那一类。
    if (facts.sdkInt >= ANDROID_13 && !facts.granted) {
        // 从没问过 → 第一次问（用户接受率最高的时机：正要听东西）。
        if (!facts.askedBefore) {
            return NotificationPermissionAction.REQUEST_SYSTEM_DIALOG
        }
        // 问过：系统说还能再弹（用户上次拒绝了但没勾「不再询问」）→ 再弹一次。
        if (facts.shouldShowRationale) {
            return NotificationPermissionAction.REQUEST_SYSTEM_DIALOG
        }
        // 问过且系统说不会再弹 ⇒ 用户永久拒绝过。
        // ⚠️ 绝不能再调 launcher.launch()：系统会直接给 result=2，
        // 用户看到的是「点了没反应」（真机实测，见 NotificationPermissionAction 的注释）。
        return NotificationPermissionAction.OPEN_SYSTEM_SETTINGS
    }

    // 剩下两种：
    // ① Android 13 以下 —— 没有运行时权限，通知开不开只在系统设置里；
    // ② 运行期权限有，但用户在系统设置里把本 App 的通知关了。
    // 两种都只能去系统设置。
    return NotificationPermissionAction.OPEN_SYSTEM_SETTINGS
}
