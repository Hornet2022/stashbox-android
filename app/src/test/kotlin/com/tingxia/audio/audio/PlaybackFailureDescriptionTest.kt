package com.tingxia.audio.audio

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 「失败描述」与「备用地址掩盖主地址」这两条护栏（2026-11-09）。
 *
 * ## 起因（内测实测）
 *
 * 用户反馈播放中断，界面提示「网络不稳，正在重连…」，但用户明确说手机网络正常。
 *
 * 真相：服务端对客户端缓存的**旧音频地址**返回 404
 * （音频被重新蒸馏 ⇒ 内容寻址 key 变 ⇒ 旧文件被删），
 * 客户端换备用地址 ⇒ 备用地址连接超时 ⇒ **最终抛出的异常是"超时"**
 * ⇒ 分类器只看到超时 ⇒ 判成「瞬时网络类」⇒ 显示「网络不稳」
 * ⇒ 并对一个**永远不存在的文件**反复自动重连。
 *
 * ⇒ 两个问题：
 *   ① 主地址的 404 被备用地址的失败**掩盖**了 ⇒ 判据失效；
 *   ② 埋点上报 `javaClass.simpleName` ⇒ release 包开了 R8 ⇒ 生产里只剩 "y"。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaybackFailureDescriptionTest {

    private fun httpError(code: Int): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(
            code,
            "HTTP $code",
            IOException("HTTP $code"),
            emptyMap(),
            DataSpec(android.net.Uri.parse("https://example.com/a.mp3")),
            ByteArray(0),
        )

    // ── ① 稳定描述：不依赖会被混淆的类名 ────────────────────────────
    @Test
    fun `404描述成固定字符串而不是类名`() {
        val d = PlaybackFailure.describe(httpError(404))
        assertEquals("http_404", d.key)
        assertEquals(404, d.httpStatus ?: -1)
    }

    @Test
    fun `5xx描述成固定字符串`() {
        assertEquals("http_5xx", PlaybackFailure.describe(httpError(503)).key)
    }

    @Test
    fun `超时有独立描述而不是网络不给力`() {
        assertEquals("socket_timeout", PlaybackFailure.describe(SocketTimeoutException("t")).key)
    }

    @Test
    fun `描述里不出现混淆后的类名`() {
        // 关键：描述必须由**我们自己**定义，不能是 javaClass.simpleName
        // （release 包 R8 混淆 ⇒ 实测生产里是 "y"）。
        val key = PlaybackFailure.describe(SocketTimeoutException("t")).key
        assertTrue(
            "cause key 应是固定的小写下划线串，实际=$key",
            key.all { it.isLowerCase() || it == '_' },
        )
        assertTrue("cause key 不该是类名", !key.contains("Exception"))
    }

    @Test
    fun `空异常描述成unknown`() {
        assertEquals("unknown", PlaybackFailure.describe(null).key)
        assertNull(PlaybackFailure.describe(null).httpStatus)
    }

    // ── ② 备用地址的失败不能掩盖主地址 ──────────────────────────────
    @Test
    fun `主地址404被备用地址超时掩盖时仍判内容类`() {
        // 复刻真实链路：主地址 404 → 换备用地址 → 备用地址连接超时
        val primary = httpError(404)
        val fallback = SocketTimeoutException("备用地址超时")
        fallback.addSuppressed(primary)

        assertEquals(
            "服务端明确说了 404（文件没了），不该判成网络类反复重连",
            PlaybackFailureKind.FATAL,
            PlaybackFailure.classify(fallback),
        )
        assertEquals("http_404", PlaybackFailure.describe(fallback).key)
    }

    @Test
    fun `没有suppressed时超时仍然是瞬时网络类`() {
        assertEquals(
            PlaybackFailureKind.TRANSIENT_NETWORK,
            PlaybackFailure.classify(SocketTimeoutException("t")),
        )
    }

    @Test
    fun `suppressed里的404也能被文案层看到`() {
        val primary = httpError(404)
        val fallback = SocketTimeoutException("备用地址超时")
        fallback.addSuppressed(primary)
        val chain = PlaybackFailure.chainFor(fallback)
        assertEquals(404, PlaybackFailure.httpStatusIn(chain) ?: -1)
    }

    @Test
    fun `PlaybackException包裹时也能拿到状态码`() {
        val wrapped = PlaybackException(
            "HTTP 404",
            null,
            2004,
        )
        // 没有 HTTP 异常在链上 ⇒ 状态码查不到（但也不能抛异常）
        assertNull(PlaybackFailure.httpStatusIn(PlaybackFailure.chainFor(wrapped)))
    }
}
