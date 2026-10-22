package com.tingxia.audio.audio

import com.tingxia.audio.di.PrimaryEndpoint
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [FallbackUrlPolicy] —— 音频那条线（Media3，不走 OkHttp）的兜底决策。
 *
 * ## 为什么单独测它
 * Media3 的 DataSpec 用 `android.net.Uri`，那个类在 JVM 单测里是 stub
 * （`Uri.parse` 返回 null）→ 直接测 [FallbackHttpDataSource] 测不了。
 * 所以把决策逻辑抽成纯函数在这里测，集成部分靠真机验证。
 */
class FallbackUrlPolicyTest {

    // 带一条真实路径的音频 URL —— 切地址时**路径不能变**（内容 hash 在路径里）
    private val primary = "https://api.stashbox.cn/audio/audio/x.mp3".toHttpUrlOrNull()!!
    private val fallbackBase = "https://8.148.9.151"

    @Before
    fun setUp() {
        // 熔断状态是**进程级共享**的 → 每个用例开始前必须重置，
        // 否则上一个用例标记的熔断会污染下一个（表现为「主地址正常时
        // 却走了备用地址」）。
        PrimaryEndpoint.markPrimaryHealthy()
    }

    @After
    fun tearDown() {
        PrimaryEndpoint.markPrimaryHealthy()
    }

    @Test
    fun `主地址健康时用主地址`() {
        PrimaryEndpoint.markPrimaryHealthy()
        assertEquals(primary, FallbackUrlPolicy.resolve(primary, fallbackBase))
    }

    @Test
    fun `熔断期用备用地址`() {
        PrimaryEndpoint.markPrimaryFailed()
        val resolved = FallbackUrlPolicy.resolve(primary, fallbackBase)
        assertEquals("8.148.9.151", resolved.host)
        assertEquals("https", resolved.scheme)
        // 路径与 query 不该被改
        assertEquals("/audio/audio/x.mp3", resolved.encodedPath)
    }

    @Test
    fun `备用地址没配时仍用主地址`() {
        PrimaryEndpoint.markPrimaryFailed()
        assertEquals(primary, FallbackUrlPolicy.resolve(primary, ""))
    }

    @Test
    fun `连接层失败要标记熔断`() {
        assertTrue(FallbackUrlPolicy.shouldMarkPrimaryFailed(UnknownHostException("api.stashbox.cn")))
        assertTrue(FallbackUrlPolicy.shouldMarkPrimaryFailed(ConnectException("refused")))
        assertTrue(FallbackUrlPolicy.shouldMarkPrimaryFailed(SocketException("Connection reset")))
    }

    @Test
    fun `读超时不标记熔断`() {
        // 服务端慢 ≠ 主地址坏了。标记了会把整个会话拖去备用地址，
        // 而备用地址同样是慢的 → 白白让用户等两个 timeout。
        assertFalse(FallbackUrlPolicy.shouldMarkPrimaryFailed(SocketTimeoutException("timeout")))
    }

    @Test
    fun `非 IO 异常不标记熔断`() {
        // HTTP 4xx/5xx 是服务端的回答，跟主地址通不通无关
        assertFalse(FallbackUrlPolicy.shouldMarkPrimaryFailed(IllegalStateException("404")))
        assertFalse(FallbackUrlPolicy.shouldMarkPrimaryFailed(null))
    }

    @Test
    fun `备用地址是否可用的判定`() {
        assertTrue(FallbackUrlPolicy.hasUsableFallback(fallbackBase))
        assertFalse(FallbackUrlPolicy.hasUsableFallback(""))
        assertFalse(FallbackUrlPolicy.hasUsableFallback("不是URL"))
    }

    @Test
    fun `IOException 之外的包装异常不标记`() {
        // 有些库会把 IOException 包一层再抛
        assertFalse(FallbackUrlPolicy.shouldMarkPrimaryFailed(RuntimeException(IOException())))
    }
}
