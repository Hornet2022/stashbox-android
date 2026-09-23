package com.tingxia.audio.di

import android.os.Build

/**
 * 客户端 → 后端基础 URL 统一出口（P1-3）。
 *
 * 之前 [NetworkModule] 与 [com.tingxia.audio.share.D9Receiver] 各自硬编码 `172.16.5.28:8100`，
 * 改真机 / 模拟器 IP 时需要两处同步。集中到这里后：
 *
 * - 真机：`http://172.16.5.28:8100/`
 * - emulator：`http://10.0.2.2:8100/`
 *
 * 判定规则：emulator Build.FINGERPRINT 通常含 `generic` / `sdk_gphone`，Build.PRODUCT 含 `sdk`。
 *
 * 注意：D9 callback 不带 Authorization（不走 Retrofit/AuthInterceptor），
 * 因此 [com.tingxia.audio.share.D9Receiver] 直接调 [gatewayBaseUrl] 拼路径，避免依赖 Retrofit 注入。
 */
object BaseUrls {
    private const val REAL_DEVICE_BASE = "http://172.16.5.28:8100/"
    private const val EMULATOR_BASE = "http://10.0.2.2:8100/"

    fun gatewayBaseUrl(): String =
        if (isRunningOnEmulator()) EMULATOR_BASE else REAL_DEVICE_BASE

    private fun isRunningOnEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.FINGERPRINT.contains("sdk_gphone", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)
}