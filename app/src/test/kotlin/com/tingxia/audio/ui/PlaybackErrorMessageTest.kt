package com.tingxia.audio.ui

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 播放失败的文案不许说假话（2026-11-09）。
 *
 * ## 起因
 *
 * 内测实测：服务端对客户端缓存的**旧音频地址**返回 404（音频被重新蒸馏 ⇒
 * 内容寻址 key 变 ⇒ 旧文件被删），客户端换备用地址、备用地址又超时 ⇒
 * 最终异常是"超时" ⇒ 文案落到 IOException 分支 ⇒ 显示「网络不给力，请检查连接」。
 *
 * ⇒ 用户看到的是"网络问题"，而真相是"**服务端说这个文件没了**"。
 * 用户的原话「手机网络正常」是对的 —— 文案在撒谎。
 *
 * ## 为什么查整条链（含 suppressed）
 *
 * 备用地址的失败会**掩盖**主地址的真实状态（主地址的异常被 addSuppressed 挂着）。
 * 只看最外层异常 ⇒ 又是"网络不给力"。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaybackErrorMessageTest {

    private fun httpError(code: Int): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(
            code,
            "HTTP $code",
            IOException("HTTP $code"),
            emptyMap(),
            DataSpec(android.net.Uri.parse("https://example.com/a.mp3")),
            ByteArray(0),
        )

    @Test
    fun `404不该说网络不给力`() {
        val msg = friendlyError(httpError(404), fallback = "播放失败，请点重试")
        assertTrue("404 的文案不该提网络，实际=$msg", !msg.contains("网络"))
        assertEquals("音频已更新或已失效，请重新进入文章", msg)
    }

    @Test
    fun `主地址404被备用地址超时掩盖时也不该说网络不给力`() {
        val primary = httpError(404)
        val fallback = SocketTimeoutException("备用地址超时")
        fallback.addSuppressed(primary)

        val msg = friendlyError(fallback, fallback = "播放失败，请点重试")
        assertTrue("掩盖后的 404 也不该提网络，实际=$msg", !msg.contains("网络"))
    }

    @Test
    fun `5xx说的是服务不可用而不是网络`() {
        val msg = friendlyError(httpError(503), fallback = "播放失败，请点重试")
        assertEquals("服务暂不可用，请稍后再试", msg)
    }

    @Test
    fun `真的是超时时仍然说网络不给力`() {
        // 判据不能矫枉过正：没有 HTTP 状态码时，超时就是网络问题
        val msg = friendlyError(SocketTimeoutException("t"), fallback = "播放失败，请点重试")
        assertEquals("网络不给力，请检查连接", msg)
    }
}
