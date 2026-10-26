package com.tingxia.audio.ui.settings

import com.tingxia.audio.ui.update.NotificationPermissionAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知设置行的文案（2026-10-26 新增）。
 *
 * ## 为什么文案也要测
 *
 * 因为文案**本身就是一个承诺**。
 *
 * 真机上踩过的坑正是文案在说谎：设置页写着「点击开启」，
 * 而用户已经永久拒绝过 ⇒ 系统框根本不会弹 ⇒ 用户点了**什么都没发生**。
 * 那句「点击开启」当时是假的。
 *
 * ⇒ 这条测试锁住：**永久拒绝那一档必须说「去系统设置」，不许出现「点击开启」**。
 * 谁把文案改回去（哪怕只改一个字），它立刻红。
 */
class NotificationRowCopyTest {

    private val purposes = NotificationRowPurpose.entries

    @Test
    fun `永久拒绝时文案说的是去系统设置`() {
        purposes.forEach { purpose ->
            val subtitle = notificationRowSubtitle(
                action = NotificationPermissionAction.OPEN_SYSTEM_SETTINGS,
                purpose = purpose,
            )
            assertTrue(
                "$purpose 的文案必须指向系统设置，实际是：$subtitle",
                subtitle.contains("系统设置"),
            )
        }
    }

    @Test
    fun `永久拒绝时文案不许承诺可以弹系统框`() {
        purposes.forEach { purpose ->
            val subtitle = notificationRowSubtitle(
                action = NotificationPermissionAction.OPEN_SYSTEM_SETTINGS,
                purpose = purpose,
            )
            assertFalse(
                "$purpose 的文案在永久拒绝时仍承诺『点击开启』：$subtitle",
                subtitle.contains("点击开启"),
            )
        }
    }

    @Test
    fun `还能弹系统框时文案说的是点击开启`() {
        purposes.forEach { purpose ->
            val subtitle = notificationRowSubtitle(
                action = NotificationPermissionAction.REQUEST_SYSTEM_DIALOG,
                purpose = purpose,
            )
            assertTrue(
                "$purpose 的文案应当引导点按，实际是：$subtitle",
                subtitle.contains("点击开启"),
            )
        }
    }

    @Test
    fun `已开启时文案说已开启且不承诺任何动作`() {
        purposes.forEach { purpose ->
            val subtitle = notificationRowSubtitle(
                action = NotificationPermissionAction.ALREADY_ON,
                purpose = purpose,
            )
            assertTrue("$purpose 的文案应说明已开启，实际是：$subtitle", subtitle.contains("已开启"))
            assertFalse(
                "$purpose 的文案在已开启时仍承诺『点击开启』：$subtitle",
                subtitle.contains("点击开启"),
            )
        }
    }

    @Test
    fun `每一档每一行的文案都非空`() {
        NotificationPermissionAction.entries.forEach { action ->
            purposes.forEach { purpose ->
                assertTrue(
                    "$action / $purpose 的文案不能为空",
                    notificationRowSubtitle(action, purpose).isNotBlank(),
                )
            }
        }
    }
}
