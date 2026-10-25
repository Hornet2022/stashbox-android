package com.tingxia.audio.util

import android.provider.Settings
import com.tingxia.audio.ui.screens.DEV_LOGIN_CODE_RANDOM_LENGTH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 埋点 device_id 的稳定性契约（2026-10-25）。
 *
 * ## 为什么 device_id 必须稳定
 *
 * 后端 `POST /api/v1/events/collect` 的限流是**按 device_id 算**的
 * （每设备 100 events/min，`backend/common/event_collect.py:16`）。
 *
 * ⇒ device_id 每次都变 = 限流形同虚设 + 同一个用户的上报散到不同桶里，
 * 后台按设备做的聚合全乱。
 *
 * 而「同一个人在两台手机上」这件事本身就该被算成两个人 ——
 * 所以稳定性的边界是「**同一台设备**」，不是「同一个账号」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceIdTest {

    private fun setAndroidId(value: String) {
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ANDROID_ID,
            value,
        )
    }

    @Test
    fun `同一台设备两次拿到同一个 device_id`() {
        setAndroidId("1234567890abcdef")

        val first = analyticsDeviceId(RuntimeEnvironment.getApplication())
        val second = analyticsDeviceId(RuntimeEnvironment.getApplication())

        assertEquals("device_id 必须稳定（限流桶按它算）", first, second)
    }

    @Test
    fun `设备指纹可用时带 and 前缀`() {
        setAndroidId("1234567890abcdef")

        val id = analyticsDeviceId(RuntimeEnvironment.getApplication())

        assertTrue("前缀要能看出来源：$id", id.startsWith("and-"))
    }

    @Test
    fun `坏 android id 时退回随机但仍然稳定`() {
        // 命中已知坏值 ⇒ 取不到可靠指纹 ⇒ 退回随机 + 落盘。
        // 关键在「仍然稳定」：如果这里每次都新生成，限流就完全失效了。
        setAndroidId(KNOWN_BAD_ANDROID_ID)

        val first = analyticsDeviceId(RuntimeEnvironment.getApplication())
        val second = analyticsDeviceId(RuntimeEnvironment.getApplication())

        assertTrue("兜底随机要带 rnd 前缀：$first", first.startsWith("rnd-"))
        assertEquals("兜底也必须稳定（落盘了）", first, second)
    }

    @Test
    fun `不同设备拿到不同 device_id`() {
        setAndroidId("aaaaaaaaaaaaaaa1")
        val first = analyticsDeviceId(RuntimeEnvironment.getApplication())

        setAndroidId("aaaaaaaaaaaaaaa2")
        val second = analyticsDeviceId(RuntimeEnvironment.getApplication())

        assertNotEquals("不同设备不能共用同一个 id", first, second)
    }

    @Test
    fun `device_id 不是 android id 原文`() {
        setAndroidId("1234567890abcdef")

        val id = analyticsDeviceId(RuntimeEnvironment.getApplication())

        assertTrue(
            "不能把 ANDROID_ID 原文送出去（那是设备标识/个人信息）：$id",
            !id.contains("1234567890abcdef"),
        )
    }

    @Test
    fun `device_id 与 dev 登录码后缀长度一致`() {
        // dev code 的后缀长度必须与来源无关（设备指纹 / 随机兜底都得是同一个长度），
        // 所以两处共用同一个长度常量 —— 这条测试防止它们各自定义后走偏。
        assertEquals(
            "设备指纹后缀长度与 dev 登录码后缀长度必须一致",
            DEVICE_ID_SUFFIX_LENGTH,
            DEV_LOGIN_CODE_RANDOM_LENGTH,
        )
    }
}
