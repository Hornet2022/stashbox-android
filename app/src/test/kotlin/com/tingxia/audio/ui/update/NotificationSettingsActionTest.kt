package com.tingxia.audio.ui.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 通知权限的**可执行动作**（2026-10-26 新增）。
 *
 * 判据（[notificationPermissionAction]）只回答「该做什么」，
 * 这里锁的是「**那个动作真的会发生**」——
 * 真机上那次的失败恰恰是：用户点了，**什么都没发生**。
 *
 * 两条守卫：
 * ① 「去系统设置」必须真的跳到本 App 的通知设置页（不是应用详情页、不是别的 App）；
 * ② 「已经问过」的记账必须落盘 —— 否则永久拒绝之后每次都以为「还没问过」，
 *    于是每次都去弹一个系统不会显示的框（= 死路复活）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationSettingsActionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `去系统设置跳的是本 App 的通知设置页`() {
        openAppNotificationSettings(context)

        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertTrue("应当启动了一个 Activity", started != null)
        assertEquals(
            Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            started!!.action,
        )
        assertEquals(
            "必须带上本 App 的包名，否则系统会打开空白页",
            context.packageName,
            started.getStringExtra(Settings.EXTRA_APP_PACKAGE),
        )
    }

    @Test
    fun `没问过的时候记账为没问过`() {
        assertFalse(NotificationPermissionAskedStore.wasAsked(context))
    }

    @Test
    fun `问过一次之后记账为问过`() {
        NotificationPermissionAskedStore.markAsked(context)
        assertTrue(NotificationPermissionAskedStore.wasAsked(context))
    }

    @Test
    fun `记账落盘而不是只在内存里`() {
        // 用另一个 Context 实例再读一次：SharedPreferences 必须能从磁盘读回来。
        // 若改成进程内变量，App 重启后就会「忘记问过」⇒ 又去弹一个系统不会显示的框。
        NotificationPermissionAskedStore.markAsked(context)
        val otherContext = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(
            "『已问过』必须落盘：App 重启后忘了问过 = 永久拒绝后还在弹框 = 死路复活",
            NotificationPermissionAskedStore.wasAsked(otherContext),
        )
    }

    // ─────────────────────────────────────────────────────────
    // 判据 → 执行：这一层才是真机上死路发生的地方
    // ─────────────────────────────────────────────────────────

    @Test
    fun `永久拒绝时跳系统设置且绝不弹系统框`() {
        // 断言的是**拒绝理由**：不该弹框就不许碰那个回调。
        // 真机实测：永久拒绝后弹框只会拿到 result=2，用户点了没反应。
        var launched = false
        performNotificationPermissionAction(
            action = NotificationPermissionAction.OPEN_SYSTEM_SETTINGS,
            context = context,
            launchRequest = { launched = true },
        )

        assertFalse("永久拒绝时绝不允许再发起系统授权框", launched)

        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertTrue("应当跳去系统通知设置", started != null)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started!!.action)
    }

    @Test
    fun `还能弹框时发起系统框且不跳系统设置`() {
        var launched = false
        performNotificationPermissionAction(
            action = NotificationPermissionAction.REQUEST_SYSTEM_DIALOG,
            context = context,
            launchRequest = { launched = true },
        )

        assertTrue("这一档必须真的发起系统授权框", launched)
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertTrue("这一档不该跳系统设置", started == null)
    }

    @Test
    fun `弹框之前就记账`() {
        // 顺序很重要：永久拒绝时系统框**不会**出现 ⇒ 回调永远不来 ⇒
        // 记账若放在回调里就永远记不上 ⇒ 每次都以为「还没问过」⇒ 死路复活。
        var wasAskedAtLaunchTime: Boolean? = null
        performNotificationPermissionAction(
            action = NotificationPermissionAction.REQUEST_SYSTEM_DIALOG,
            context = context,
            launchRequest = {
                wasAskedAtLaunchTime = NotificationPermissionAskedStore.wasAsked(context)
            },
        )
        assertEquals(true, wasAskedAtLaunchTime)
    }

    @Test
    fun `已开启时什么都不做`() {
        var launched = false
        performNotificationPermissionAction(
            action = NotificationPermissionAction.ALREADY_ON,
            context = context,
            launchRequest = { launched = true },
        )
        assertFalse("已开启时不该再发起授权", launched)
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertTrue("已开启时不该跳系统设置", started == null)
    }

    // ─────────────────────────────────────────────────────────
    // 升级路径上的兜底：从旧版本升上来的用户，App 自己那份
    // 「已经问过」的标记并不存在 ⇒ 判据会说「还没问过」⇒ 去弹框
    // ⇒ 而系统其实早就不弹了 ⇒ 死路。靠回调兜底。
    // ─────────────────────────────────────────────────────────

    @Test
    fun `系统拒绝再弹时回调后要立刻把人送到系统设置`() {
        // 未授权 + 系统说不会再问 = 系统拒绝了弹框
        assertTrue(
            shouldFallbackToSystemSettings(granted = false, shouldShowRationale = false),
        )
    }

    @Test
    fun `用户只是拒绝了一次时不急着跳系统设置`() {
        // 系统说还能再问 ⇒ 下次弹框是有机会的 ⇒ 别把人支走
        assertFalse(
            shouldFallbackToSystemSettings(granted = false, shouldShowRationale = true),
        )
    }

    @Test
    fun `用户授权了就不该跳系统设置`() {
        assertFalse(
            shouldFallbackToSystemSettings(granted = true, shouldShowRationale = false),
        )
    }
}
