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
 * ⚠️ 这两条**只对 debug / minifyDebug 成立**；release 的规则见下节（BUG#2）。
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
 * ## release 不许用局域网地址（2026-10-06，BUG#2）
 *
 * 下面这套 `Mac-mini.local` / `192.168.3.100` 兜底**只在 debug / minifyDebug 里成立**。
 * 它俩都在 `network_security_config.xml` 的明文白名单里，而这份白名单会跟着
 * release 包一起出厂 —— 也就是说 release APK 在任何一台**不在作者家局域网**的设备上，
 * 只能连那台机器，第一个请求必然失败（`UnknownHostException` / 明文被拦）。
 * 这种包能装、能启动、登录才炸，正是最坏的一类出货缺陷。
 *
 * 所以 release 的取值规则改成：**只认构建期显式给的地址，没给就在构建期直接失败**
 * （守卫见 `app/build.gradle.kts` 的「release 前提守卫」，通过
 * `-PapiBaseUrl` / `TINGXIA_API_BASE_URL` 传入）。这里再加一道运行期兜底：
 * 万一有人绕过守卫拼出一个 release 产物，也在**第一次用时立刻崩**，
 * 而不是静默去连一台只有作者家里能连的机器。
 *
 * 公网域名定下来后，release 只需构建时带上它，不需要改这里的任何常量。
 * 仍要一并处理的两处配套（否则上公网后必然出问题）：
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
     * 当前是不是 release 产物。
     *
     * 用 `BuildConfig.BUILD_TYPE` 判，而不是 `BuildConfig.DEBUG`：后者对
     * `minifyDebug` 是 false，而那个变体恰恰是**真机验证 R8** 用的
     * （2026-10-03，见 app/build.gradle.kts 里的注释），必须继续允许连局域网。
     */
    private val isReleaseBuild: Boolean =
        BuildConfig.BUILD_TYPE.equals("release", ignoreCase = true)

    /**
     * 真机用的服务端主机。mDNS 名，不随 DHCP 变化。
     * 上公网后改成域名（保留 scheme 与端口约定；若走 https/443 则端口段置空）。
     */
    private const val REAL_DEVICE_HOST = "Mac-mini.local"

    /** 端口。公网若是标准 80/443，这里应改成空串。 */
    private const val REAL_DEVICE_PORT = "8100"

    private const val EMULATOR_BASE = "http://10.0.2.2:8100/"

    /**
     * 开发用兜底局域网 IP（Mac mini 的当前 DHCP 地址）。
     * release 不暴露它 —— 出口是 [FALLBACK_LAN_IP]（release 下为空串）。
     */
    private const val DEV_FALLBACK_LAN_IP = "192.168.3.100"

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

    /**
     * App 实际连的后端。
     *
     * debug / minifyDebug：显式覆盖优先，否则 emulator → 10.0.2.2、真机 → Mac-mini.local。
     * release：**只认显式覆盖**。没给就抛，而不是回落到局域网地址 ——
     * 见类注释「release 不许用局域网地址」（BUG#2）。正常流程下 release 根本到不了
     * 这一行（构建期守卫先失败），这里只是防止有人绕过守卫产出「能装、连不上」的包。
     */
    fun gatewayBaseUrl(): String {
        if (buildTimeOverride.isNotBlank()) return buildTimeOverride
        check(!isReleaseBuild) {
            "release 构建没有配置后端地址（-PapiBaseUrl / TINGXIA_API_BASE_URL）。" +
                "release 已禁用局域网兜底（$REAL_DEVICE_HOST:$REAL_DEVICE_PORT / ${lanFallbackIpOrDev()}），" +
                "回落到它会让装出去的包在作者家之外一个请求都发不出去。请带 -PapiBaseUrl=... 重新构建。"
        }
        return if (isRunningOnEmulator()) EMULATOR_BASE else "http://$REAL_DEVICE_HOST:$REAL_DEVICE_PORT/"
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
     * 真要根治，应该上公网域名（见类注释「release 不许用局域网地址」）。
     *
     * **release 里它是空串**（2026-10-06，BUG#2）：连同 [REAL_DEVICE_HOST_NAME] 一起
     * 置空，兜底路径在 release 里就**根本匹配不到任何 host** ——
     * 免得哪天有人绕过构建期守卫，这个写死的 DHCP 地址仍然能被一个 release 包用上。
     * [MdnsFallbackDns] 的比对是 `hostname == REAL_DEVICE_HOST_NAME`，空串匹配不到
     * 任何真实主机，于是它不会走到 `InetAddress.getByName("")` 那条路。
     */
    val FALLBACK_LAN_IP: String = if (isReleaseBuild) "" else DEV_FALLBACK_LAN_IP

    /**
     * 需要兜底判断的主机名。别的 host 解析失败要照常抛错，别把真实 DNS 问题一起吞掉。
     * release 里为空串（原因同 [FALLBACK_LAN_IP]）。
     */
    val REAL_DEVICE_HOST_NAME: String = if (isReleaseBuild) "" else REAL_DEVICE_HOST

    /** 只用于报错文案：把 release 下被禁用的局域网地址原样告诉操作者。 */
    private fun lanFallbackIpOrDev(): String =
        if (isReleaseBuild) "已禁用" else DEV_FALLBACK_LAN_IP

    private fun isRunningOnEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.FINGERPRINT.contains("sdk_gphone", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)
}
