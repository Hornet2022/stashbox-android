package com.tingxia.audio.audio.di

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 播放器的**平台行为**配置（2026-10-23 新增）。
 *
 * ## 为什么用源码断言而不是运行时断言
 *
 * 这三项（音频焦点 / 息屏唤醒 / 耳机拔出）都是 **ExoPlayer.Builder 的配置**，
 * 配完之后在运行时**读不回来**（没有 getter）。所以想验「配了没有」，
 * 只能看构建出来的实例 —— 而 Builder 的配置项不暴露在实例上。
 *
 * 于是退一步：断言**配置确实写在 PlayerModule 里**。
 * 这不是源码匹配式的形式主义 —— 判据是「这三条平台行为在本文件里被定义过
 * 一次」，而「别处再定义一遍」正是这类配置最容易出问题的地方
 * （同一个 App 里两处都设了音频焦点 = 其中一处是多余的）。
 *
 * ## 这三条各自解决什么
 *
 * - **音频焦点**：不设的话别的 App 在放音乐时听匣照放不误；来电/导航提示
 *   也不打断、不 duck。⚠️ Android 15（= 本项目 targetSdk 35）还有一条硬要求：
 *   请求焦点时 App 必须是顶层 App 或跑着前台服务，否则
 *   `requestAudioFocus()` 直接返回 `AUDIOFOCUS_REQUEST_FAILED` ——
 *   **声音会完全出不来**。
 * - **息屏唤醒**（`setWakeMode`）：应用进程是普通优先级，息屏后 CPU 被挂起
 *   → 播放中断。权限 WAKE_LOCK 早已在清单里声明，一直没用上。
 * - **耳机拔出**（`setHandleAudioBecomingNoisy`）：拔了耳机还在外放。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerModulePlatformBehaviourTest {

    private fun source(): String {
        val file = File("src/main/kotlin/com/tingxia/audio/audio/di/PlayerModule.kt")
        assertTrue("找不到 PlayerModule.kt（路径变了？）", file.exists())
        return file.readText()
    }

    @Test
    fun `开了音频焦点处理`() {
        val src = source()
        assertTrue(
            "必须开音频焦点处理（handleAudioFocus=true）—— 否则与别的 App 抢着放",
            src.contains("handleAudioFocus = */ true") || src.contains("handleAudioFocus = true"),
        )
        assertTrue(
            "必须声明音频属性（USAGE_MEDIA + CONTENT_TYPE_SPEECH）",
            src.contains("C.USAGE_MEDIA") && src.contains("C.CONTENT_TYPE_SPEECH"),
        )
    }

    @Test
    fun `开了息屏唤醒`() {
        assertTrue(
            "必须 setWakeMode(C.WAKE_MODE_LOCAL) —— 否则息屏后 CPU 被挂起，播放中断",
            source().contains("setWakeMode(C.WAKE_MODE_LOCAL)"),
        )
    }

    @Test
    fun `开了耳机拔出自动暂停`() {
        assertTrue(
            "必须 setHandleAudioBecomingNoisy(true) —— 否则拔耳机还在外放",
            source().contains("setHandleAudioBecomingNoisy(true)"),
        )
    }

    @Test
    fun `快进快退步长与 App 内 UI 一致`() {
        val src = source()
        // App 内 UI 的既有约定：后退 10 秒 / 快进 30 秒
        // （FullScreenPlayerScreen 的 Replay10 / Forward30）。
        assertTrue("后退步长应为 10 秒", src.contains("SEEK_BACK_MS = 10_000L"))
        assertTrue("快进步长应为 30 秒", src.contains("SEEK_FORWARD_MS = 30_000L"))
        assertTrue(
            "步长必须真的喂给 ExoPlayer（否则耳机键/系统面板用的是默认 5s/15s）",
            src.contains("setSeekBackIncrementMs(SEEK_BACK_MS)") &&
                src.contains("setSeekForwardIncrementMs(SEEK_FORWARD_MS)"),
        )
    }
}
