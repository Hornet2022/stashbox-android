package com.tingxia.audio.ui.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知权限申请时机的规则。
 *
 * 锁住三条：
 * - Android 13 以下不需要（也没有）运行时通知权限，不该弹框；
 * - 已授权时不重复弹框（重复弹会被系统直接拒绝，用户还以为是 bug）；
 * - **用户永久拒绝过时不再弹框**（系统已经不会弹了，弹了就是死路）。
 */
class PushPermissionPolicyTest {

    @Test
    fun `Android 13 以下不需要申请`() {
        assertFalse(needRequestNotificationPermission(sdkInt = 30, permissionGranted = false))
    }

    @Test
    fun `Android 13 及以上未授权时需要申请`() {
        assertTrue(needRequestNotificationPermission(sdkInt = 33, permissionGranted = false))
        assertTrue(needRequestNotificationPermission(sdkInt = 35, permissionGranted = false))
    }

    @Test
    fun `已授权时不重复申请`() {
        assertFalse(needRequestNotificationPermission(sdkInt = 33, permissionGranted = true))
    }

    @Test
    fun `用户永久拒绝后不再弹系统框`() {
        // 真机实测：永久拒绝后 GrantPermissionsActivity 回 result=2（不再显示），
        // 弹框 = 用户点了没反应。详见 NotificationPermissionPolicyTest 的说明。
        assertFalse(
            needRequestNotificationPermission(
                sdkInt = 34,
                permissionGranted = false,
                notificationsEnabled = false,
                askedBefore = true,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun `用户拒绝过但系统还允许再弹时可以再弹`() {
        assertTrue(
            needRequestNotificationPermission(
                sdkInt = 34,
                permissionGranted = false,
                notificationsEnabled = false,
                askedBefore = true,
                shouldShowRationale = true,
            ),
        )
    }
}
