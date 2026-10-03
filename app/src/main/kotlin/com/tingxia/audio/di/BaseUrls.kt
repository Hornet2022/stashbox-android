package com.tingxia.audio.di

import android.os.Build
import com.tingxia.audio.BuildConfig

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

    /**
     * 构建期覆盖（`-PapiBaseUrl=...`），空串表示没传。
     *
     * 存在的理由：有些状态只在真机上验得了，但造它们要写库 —— 配额耗尽
     * （付费墙 / QuotaBanner）、蒸馏中（状态徽章文案）。没有这个开关时
     * 想换后端只能改上面两个常量重编译，而那些常量是**线上配置**。
     *
     * 指向 e2e 后端（admin-web 的 `e2e-backend.sh`，独立 stashbox_e2e 库）
     * 时测试数据落在隔离库，生产库零写入。默认空 = 行为完全不变。
     *
     * ⚠️ 目标 host 必须在 `network_security_config.xml` 的明文白名单里，
     * 否则 App 能装能启动但一个请求都发不出去（编译期和单测都发现不了）。
     */
    private val buildTimeOverride: String = BuildConfig.API_BASE_URL

    /** true 表示这次构建显式指定了后端，日志里要标出来，别让人以为在连生产。 */
    val isOverridden: Boolean get() = buildTimeOverride.isNotBlank()

    fun gatewayBaseUrl(): String =
        buildTimeOverride.ifBlank {
            if (isRunningOnEmulator()) EMULATOR_BASE else "http://$REAL_DEVICE_HOST:$REAL_DEVICE_PORT/"
        }

    /**
     * CP-ANDROID-MDNS-FALLBACK：mDNS 解析失败时的兜底局域网 IP。
     *
     * **为什么需要兜底** —— mDNS 在部分真机上根本不工作。实测华为 JEF-AN20
     * （Android 12 / EMUI）与 Mac mini 同网段（192.168.3.12 ↔ 192.168.3.100）、
     * 手机浏览器直接访问 `http://192.168.3.100:8100/healthz` 能拿到
     * `{"status":"ok"}`，但 `mac-mini.local` 一律 `UnknownHostException`。
     * 于是 App 能装能启动，登录却直接失败（`POST /api/v1/auth/token` 报
     * `Unable to resolve host "mac-mini.local"`）—— 编译期和单测都发现不了，
     * 只有真机能暴露。
     *
     * 失败不一定来自设备：路由器不开组播反射 / AP 隔离也会造成同样现象，
     * 换设备未必能绕开。所以不能只靠「mDNS 一定可用」这个假设。
     *
     * **兜底怎么生效** —— 走 [MdnsFallbackDns]（OkHttp 的可插拔 `Dns`），
     * **不改 URL 里的主机名**。这一点很关键：Android 的明文校验
     * （`network_security_config.xml`）看的是 URL 的 host，不是解析后的 IP，
     * 所以 host 仍是 `mac-mini.local` 时，现有白名单就继续生效，
     * 不必把局域网 IP 也加进白名单、少一处会随 IP 漂移而失效的配置。
     *
     * ⚠️ 这个 IP 是**当前 Mac mini 的 DHCP 地址，换网络后要改这里重编译**。
     * 它只影响「mDNS 恰好不可用」这一个降级路径；mDNS 正常时压根不会用到。
     * 真要根治，应该上公网域名（见 CP-ANDROID-PUBLIC-DOMAIN）。
     */
    const val FALLBACK_LAN_IP = "192.168.3.100"

    /** 需要兜底判断的主机名。别的 host 解析失败要照常抛错，别把真实 DNS 问题一起吞掉。 */
    const val REAL_DEVICE_HOST_NAME = REAL_DEVICE_HOST

    private fun isRunningOnEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.FINGERPRINT.contains("sdk_gphone", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)
}
