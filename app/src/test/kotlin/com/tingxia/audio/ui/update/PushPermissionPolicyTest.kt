package com.tingxia.audio.ui.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知权限申请时机的规则。
 *
 * 锁住两条：
 * - Android 13 以下不需要（也没有）运行时通知权限，不该弹框；
 * - 已授权时不重复弹框（重复弹会被系统直接拒绝，用户还以为是 bug）。
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
}
