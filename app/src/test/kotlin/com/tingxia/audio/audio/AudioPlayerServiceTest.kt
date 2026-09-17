package com.tingxia.audio.audio

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AudioPlayerService 单测（CP4.5）。
 *
 * 验证 [AudioPlayerService.onCreate] 通过 [androidx.media3.session.MediaSessionService.setMediaNotificationProvider]
 * 注入了 Media3 自带的 [androidx.media3.session.DefaultMediaNotificationProvider]（自动处理锁屏 UI + MediaStyle 通知），
 * 而非写自定义 NotificationBuilder。
 *
 * 锁屏 UI 真渲染需要 emulator，这里只验 provider 已被设置（[MediaSessionService] 内部私有字段）。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioPlayerServiceTest {

    @Test
    fun `onCreate sets media notification provider`() {
        val service = Robolectric.buildService(AudioPlayerService::class.java).create().get()

        // MediaSessionService 把 provider 存进私有字段 mediaNotificationProvider，
        // 没有公开 getter，沿继承链反射取出断言非空。
        var clazz: Class<*> = AudioPlayerService::class.java
        var field: java.lang.reflect.Field? = null
        while (clazz != null && field == null) {
            field = runCatching { clazz.getDeclaredField("mediaNotificationProvider") }.getOrNull()
            clazz = clazz.superclass
        }
        checkNotNull(field) { "mediaNotificationProvider field not found on MediaSessionService" }
        field.isAccessible = true

        val provider = field.get(service)
        assertNotNull("DefaultMediaNotificationProvider 应在 onCreate 中被设置", provider)
    }
}
