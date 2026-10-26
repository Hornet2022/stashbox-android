package com.tingxia.audio.ui.screens

import com.tingxia.audio.ui.update.NotificationPermissionAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放器页的「通知栏控制条不可用」提示文案（2026-10-26 新增）。
 *
 * 锁的是同一件事：**文案承诺的动作必须真的存在**。
 *
 * 永久拒绝那一档，系统授权框已经不会再弹了（真机实测 result=2），
 * 所以文案必须说「去系统设置」；写成「点这里开启」就是在骗用户。
 */
class NotificationControlHintTextTest {

    @Test
    fun `永久拒绝时提示语指向系统设置`() {
        val text = notificationControlHintText(NotificationPermissionAction.OPEN_SYSTEM_SETTINGS)
        assertTrue("应当指向系统设置，实际是：$text", text.contains("系统设置"))
    }

    @Test
    fun `永久拒绝时不许承诺可以当场弹框`() {
        val text = notificationControlHintText(NotificationPermissionAction.OPEN_SYSTEM_SETTINGS)
        assertFalse("仍在承诺当场开启：$text", text.contains("点这里开启"))
    }

    @Test
    fun `还能弹框时提示语说点这里开启`() {
        val text = notificationControlHintText(NotificationPermissionAction.REQUEST_SYSTEM_DIALOG)
        assertTrue("应当引导点按，实际是：$text", text.contains("点这里开启"))
    }

    @Test
    fun `已开启时不显示提示`() {
        assertTrue(
            notificationControlHintText(NotificationPermissionAction.ALREADY_ON).isBlank(),
        )
    }
}
