package com.tingxia.audio.di

import android.os.Build

/**
 * 客户端 → 后端基础 URL 统一出口（P1-3）。
 *
 * 之前 [NetworkModule] 与 [com.tingxia.audio.share.D9Receiver] 各自硬编码 `172.16.5.28:8100`，
 * 改真机 / 模拟器 IP 时需要两处同步。集中到这里后：
 *
 * - 真机：`http://Mac-mini.local:8100/`
 * - emulator：`http://10.0.2.2:8100/`
 *
 * 判定规则：emulator Build.FINGERPRINT 通常含 `generic` / `sdk_gphone`，Build.PRODUCT 含 `sdk`。
 *
 * ## 为什么用 mDNS 主机名而不是 IP（CP-ANDROID-MDNS）
 *
 * 原先硬编码 `172.16.5.28`。那是**动态分配**的地址 —— DHCP 续租/换网络后 IP 变了，
 * 客户端就连不上服务端，而这里没有任何改配置的入口，只能重编译。
 *
 * `Mac-mini.local` 走 mDNS 解析，**不随 DHCP 变化**，同一局域网内都能用。
 *
 * ## 将来切公网域名（CP-ANDROID-PUBLIC-DOMAIN）
 *
 * 本地开发完成后会部署到公网并绑域名。到时**只改下面两个常量**即可，
 * 不用再全工程搜 IP：
 *
 *     [REAL_DEVICE_HOST]  → api.你的域名.com
 *     [REAL_DEVICE_PORT]  → 若走标准 443 改成空串
 *
 * 同时要改的两处配套（否则上公网后必然出问题）：
 *   1. `res/xml/network_security_config.xml` —— 把该域名加进明文白名单，
 *      或彻底改 https 后从白名单移除；
 *   2. 服务端 `OSS_PUBLIC_BASE_URL` —— 音频 URL 由服务端
 *      `articles.audio_url` 下发（App 不拼音频地址），所以音频侧改服务端即可。
 *
 * 注意：D9 callback 不带 Authorization（不走 Retrofit/AuthInterceptor），
 * 因此 [com.tingxia.audio.share.D9Receiver] 直接调 [gatewayBaseUrl] 拼路径，避免依赖 Retrofit 注入。
 */
object BaseUrls {
    /**
     * 真机用的服务端主机。mDNS 名，不随 DHCP 变化。
     * 上公网后改成域名（保留 scheme 与端口约定；若走 https/443 则端口段置空）。
     */
    private const val REAL_DEVICE_HOST = "Mac-mini.local"

    /** 端口。公网若是标准 80/443，这里应改成空串。 */
    private const val REAL_DEVICE_PORT = "8100"

    private const val EMULATOR_BASE = "http://10.0.2.2:8100/"

    fun gatewayBaseUrl(): String =
        if (isRunningOnEmulator()) EMULATOR_BASE else "http://$REAL_DEVICE_HOST:$REAL_DEVICE_PORT/"

    private fun isRunningOnEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.FINGERPRINT.contains("sdk_gphone", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)
}
