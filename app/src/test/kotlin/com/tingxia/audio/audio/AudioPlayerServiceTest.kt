package com.tingxia.audio.audio

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * AudioPlayerService 单测（CP4.5，2026-10-23 扩充）。
 *
 * 验证 [AudioPlayerService.onCreate] 通过 [androidx.media3.session.MediaSessionService.setMediaNotificationProvider]
 * 注入了 Media3 自带的 [androidx.media3.session.DefaultMediaNotificationProvider]（自动处理锁屏 UI + MediaStyle 通知），
 * 而非写自定义 NotificationBuilder。
 *
 * 锁屏 UI 真渲染需要 emulator，这里只验 provider 已被设置（[MediaSessionService] 内部私有字段）。
 *
 * ## 2026-10-23 扩充：两条真机上炸过、这里原来抓不到的
 *
 * 1. **会话必须登记给服务**（`addSession`）。
 *    Media3 从 1.4.0 起不再自动接管 `onGetSession` 返回的会话 ——
 *    不调 `addSession()`，服务就不知道自己有会话 → **不发通知、不进前台**。
 *    真机症状：声音照放、进度照回传、`dumpsys media_session` 里也看得到会话，
 *    只有通知不见 → 极易被误判成「Android 13+ 通知权限」，去申请权限、加渠道
 *    全都不是这里。
 * 2. **自定义按钮的图标要用「图标常量」**。
 *    `CommandButton.Builder(int)` 收的是 `CommandButton.ICON_*` 常量，
 *    不是 drawable 资源 id —— 传错会映射成 0，
 *    Media3 建 CustomAction 时抛
 *    "You must specify an icon resource id to build a CustomAction"，
 *    **服务在 onCreate 里就崩**（App 一起身就退）。
 *
 * 两条都**必须 idle 主线程**才测得到：Media3 把「同步给旧版会话（锁屏/车机）」
 * 的任务**投递到主线程**，不跑那一跳就什么都不会发生 ——
 * 于是「原来那个测试」对这些错误是**全盲**的。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioPlayerServiceTest {

    private var service: AudioPlayerService? = null
    private var serviceController: org.robolectric.android.controller.ServiceController<AudioPlayerService>? = null

    @After
    fun tearDown() {
        // 必须销毁服务：Media3 用一个**静态**的 id→会话 映射表做唯一性检查，
        // 不释放的话下一个用例建会话会撞 "Session ID must be unique"。
        //
        // 只销毁服务（不要额外 release）：服务自己的 onDestroy 里会
        // `removeSession` + `release` —— 而 removeSession 断言会话**还在**服务里，
        // 提前 release 过就会抛 "session not found"。
        serviceController?.destroy()
        serviceController = null
        service = null
    }

    private fun createService(): AudioPlayerService {
        val controller = Robolectric.buildService(AudioPlayerService::class.java).create()
        serviceController = controller
        return controller.get().also { service = it }
    }

    @Test
    fun `onCreate sets media notification provider`() {
        val svc = createService()

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

        val provider = field.get(svc)
        assertNotNull("DefaultMediaNotificationProvider 应在 onCreate 中被设置", provider)
    }

    @Test
    fun `会话登记给了服务——否则没有通知也没有前台服务`() {
        val svc = createService()

        // Media3 1.4.0 起要显式 addSession()。
        // getSessions() 是 MediaSessionService 的公开 API，直接断言。
        val sessions: List<MediaSession> = svc.sessions
        assertTrue(
            "会话必须 addSession 给服务 —— 否则服务不发通知、不进前台" +
                "（真机症状：声音照放但通知栏没有控制条）",
            sessions.isNotEmpty(),
        )
    }

    @Test
    fun `onCreate 之后把主线程跑一遍也不崩`() {
        // 这条专门盯「只有跑起来才炸」的错误：自定义按钮的图标常量传错 →
        // Media3 投递一个任务到主线程去同步旧版会话 → 那一跳里抛
        // IllegalArgumentException → 服务在 onCreate 就崩。
        // 不 idle 主线程的话，这一步根本不会执行，错误在单测里是**看不见**的。
        createService()

        shadowOf(Looper.getMainLooper()).idle()

        // 能走到这里就说明没抛异常。
        assertEquals(0, 0)
    }

    @Test
    fun `通知点击有落地页——没有 sessionActivity 点通知什么都不会发生`() {
        val svc = createService()
        shadowOf(Looper.getMainLooper()).idle()

        val session = svc.sessions.firstOrNull()
        assertNotNull("服务里应该有会话", session)
        val activity = session!!.sessionActivity
        assertNotNull(
            "必须设 sessionActivity —— DefaultMediaNotificationProvider 的通知内容意图" +
                "就是 mediaSession.getSessionActivity()，为 null 时**点通知没反应**",
            activity,
        )
    }
}
