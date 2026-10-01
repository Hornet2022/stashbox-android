package com.tingxia.audio.di

import android.util.Log
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * CP-ANDROID-MDNS-FALLBACK：mDNS 解析失败时降级到局域网 IP。
 *
 * 背景见 [BaseUrls.FALLBACK_LAN_IP] 的注释 —— 华为 JEF-AN20 真机上
 * `mac-mini.local` 一律解析不了，但同网段的 `192.168.3.100:8100` 完全可达。
 *
 * ## 为什么放在 `Dns` 这一层，而不是改 base url
 *
 * 1. **不动明文白名单**。Android 的 `NetworkSecurityPolicy` 校验的是 URL 里的
 *    host，不是解析出来的 IP。base url 仍是 `http://Mac-mini.local:8100/`，
 *    所以 `network_security_config.xml` 里那条 `mac-mini.local` 继续生效；
 *    换成 IP 当 host 的话反而要把 IP 也塞进白名单，多一处会随 DHCP 漂移的配置。
 * 2. **不阻塞主线程**。判断放在建连时由 OkHttp 的调用线程执行；
 *    如果改成启动时同步 `InetAddress.getByName` 探活，DNS 超时会卡住 UI 线程。
 * 3. **两个 client 一处覆盖**。Retrofit（[NetworkModule]）和 D9
 *    （[com.tingxia.audio.share.D9Receiver]）各自建 OkHttpClient，
 *    但都注入同一个 `Dns` 即可，不用在两处写判断逻辑。
 * 4. **只兜底该兜的**。非 mDNS 主机解析失败一律照常抛 `UnknownHostException`，
 *    免得把真实的 DNS / 服务端域名问题一起吞掉，排查时反而看不见。
 *
 * ## 代价
 *
 * 兜底 IP 写死在 [BaseUrls.FALLBACK_LAN_IP]，Mac mini 换网络后要改它重编译。
 * mDNS 正常时这条路径根本不会触发。
 */
class MdnsFallbackDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val fallbackIp: String = BaseUrls.FALLBACK_LAN_IP,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val resolved =
            try {
                delegate.lookup(hostname)
            } catch (e: UnknownHostException) {
                if (!isMdnsHost(hostname)) throw e
                Log.w(
                    TAG,
                    "mDNS 解析失败，降级到局域网 IP：hostname=$hostname fallback=$fallbackIp",
                    e,
                )
                return listOf(InetAddress.getByName(fallbackIp))
            }
        // Dns.SYSTEM 正常时不会返空列表，但自定义 delegate 可能返空。
        // 空列表对 OkHttp 来说等同于「解析不出」，同样要能兜底。
        if (resolved.isNotEmpty() || !isMdnsHost(hostname)) return resolved

        Log.w(TAG, "mDNS 返空结果，降级到局域网 IP：hostname=$hostname fallback=$fallbackIp")
        return listOf(InetAddress.getByName(fallbackIp))
    }

    private fun isMdnsHost(hostname: String): Boolean =
        hostname.equals(BaseUrls.REAL_DEVICE_HOST_NAME, ignoreCase = true)

    private companion object {
        const val TAG = "MdnsFallbackDns"
    }
}
