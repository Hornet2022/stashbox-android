package com.tingxia.audio.ui.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知权限判据（2026-10-26 新增）。
 *
 * 锁的是**每一个事实组合都会落到一个动作上**，以及最关键的那一格：
 * **用户永久拒绝过 ⇒ 只能去系统设置，绝不能再弹系统框**。
 *
 * ## 为什么这格是重点
 *
 * 真机实测（MIUI 14 / Android 14，0.4.2 包）：把权限设成永久拒绝后
 * 再点设置页那一行，logcat 是
 *
 *     V GrantPermissionsActivity: Permission grant result ...
 *       permission=android.permission.POST_NOTIFICATIONS isImplicit=false result=2
 *
 * `result=2` = `RESULT_PERMISSION_FIXED` —— **系统拒绝再弹授权框**。
 * 屏幕上零弹窗，副标题还写着「点击开启」⇒ 用户点了**什么都没发生**。
 *
 * 反向对照（把 `user-fixed` 标志去掉后再点同一个按钮）：
 * `GrantPermissionsActivity` 立刻拉起 ⇒ 死路的原因就是这个标志位，
 * 不是 onClick 写错了。
 *
 * ⇒ 这条测试就是那次死路的守卫：谁把这一格改回「弹系统框」，它立刻红。
 */
class NotificationPermissionPolicyTest {

    private fun facts(
        sdkInt: Int = 34,
        granted: Boolean = false,
        notificationsEnabled: Boolean = true,
        askedBefore: Boolean = false,
        shouldShowRationale: Boolean = false,
    ) = NotificationPermissionFacts(
        sdkInt = sdkInt,
        granted = granted,
        notificationsEnabled = notificationsEnabled,
        askedBefore = askedBefore,
        shouldShowRationale = shouldShowRationale,
    )

    @Test
    fun `权限与系统开关都正常时不需要任何动作`() {
        assertEquals(
            NotificationPermissionAction.ALREADY_ON,
            notificationPermissionAction(facts(granted = true, notificationsEnabled = true)),
        )
    }

    @Test
    fun `Android 13 以上没授权且从没问过时要弹系统框`() {
        assertEquals(
            NotificationPermissionAction.REQUEST_SYSTEM_DIALOG,
            notificationPermissionAction(facts(sdkInt = 33, granted = false, askedBefore = false)),
        )
    }

    @Test
    fun `Android 13 以上没问过时不因为系统开关是关的就跳过申请`() {
        // 关键：API 33+ 上「没拿到运行期权限」时 areNotificationsEnabled() 必然是 false。
        // 若把这条写成「系统开关关了就直接去设置」，用户第一次玩就永远拿不到授权框。
        assertEquals(
            NotificationPermissionAction.REQUEST_SYSTEM_DIALOG,
            notificationPermissionAction(
                facts(sdkInt = 33, granted = false, notificationsEnabled = false, askedBefore = false),
            ),
        )
    }

    @Test
    fun `用户拒绝过但系统还允许再弹时可以再弹一次`() {
        assertEquals(
            NotificationPermissionAction.REQUEST_SYSTEM_DIALOG,
            notificationPermissionAction(
                facts(sdkInt = 34, granted = false, askedBefore = true, shouldShowRationale = true),
            ),
        )
    }

    @Test
    fun `用户永久拒绝过时只能去系统设置`() {
        // ← 本次修复的核心断言。真机实测 result=2：再弹系统框是死路。
        assertEquals(
            NotificationPermissionAction.OPEN_SYSTEM_SETTINGS,
            notificationPermissionAction(
                facts(sdkInt = 34, granted = false, askedBefore = true, shouldShowRationale = false),
            ),
        )
    }

    @Test
    fun `运行期权限有但用户在系统里关了本 App 通知时只能去系统设置`() {
        // MIUI 单独关掉某个 App 通知是常态，而运行期权限仍然是 granted。
        // 只看 granted 会得出「已经能发通知」的错误结论 ⇒ 通知栏就是空的。
        assertEquals(
            NotificationPermissionAction.OPEN_SYSTEM_SETTINGS,
            notificationPermissionAction(facts(sdkInt = 34, granted = true, notificationsEnabled = false)),
        )
    }

    @Test
    fun `Android 13 以下没有运行时权限只能去系统设置`() {
        assertEquals(
            NotificationPermissionAction.OPEN_SYSTEM_SETTINGS,
            notificationPermissionAction(facts(sdkInt = 30, granted = false, notificationsEnabled = false)),
        )
    }

    @Test
    fun `Android 13 以下系统开着就算已开启`() {
        assertEquals(
            NotificationPermissionAction.ALREADY_ON,
            notificationPermissionAction(facts(sdkInt = 30, granted = true, notificationsEnabled = true)),
        )
    }

    @Test
    fun `穷举所有事实组合都落到一个动作上`() {
        // 「不许有第四种状态」是这次修复的底线：
        // 任何一种组合都必须有一个**可执行**的动作，
        // 否则就会出现「点了没反应」——用户反馈的那句话。
        val combos = listOf(26, 30, 33, 34, 35)
            .flatMap { sdk ->
                listOf(true, false).flatMap { granted ->
                    listOf(true, false).flatMap { enabled ->
                        listOf(true, false).flatMap { asked ->
                            listOf(true, false).map { rationale ->
                                facts(sdk, granted, enabled, asked, rationale)
                            }
                        }
                    }
                }
            }
        // 5 个 sdk × 2^4 个布尔 = 80 种组合
        assertEquals(5 * 16, combos.size)
        combos.forEach { f ->
            val action = notificationPermissionAction(f)
            assertTrue(
                "事实组合 $f 必须落到一个动作上",
                action in NotificationPermissionAction.entries,
            )
        }
    }
}
