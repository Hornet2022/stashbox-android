package com.tingxia.audio.audio

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 播放失败的分类（2026-10-26）。
 *
 * ## 为什么这一层值得单独测
 *
 * 分类是「自动重连」与「直接报错」的分岔点，判错的后果都很贵：
 *
 * · 把**内容类**判成网络类 → 对一个已经消失的音频无限重连（后台耗电循环）；
 * · 把**网络类**判成内容类 → 一条抖动就变成永久停止（原 bug）。
 *
 * ⇒ 这一层必须是**纯逻辑**（不碰 ExoPlayer、不碰网络），
 * 才能把上面两边的错都钉住。
 *
 * 特别要盯住的一条：`InvalidResponseCodeException` **本身也是 IOException**
 * （它继承 `HttpDataSourceException`）。若先按 IOException 判，
 * 404 会被误判成「网络问题」⇒ 对不存在的音频无限重连。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaybackFailureTest {

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
    fun `连接被重置算瞬时网络类`() {
        assertEquals(
            PlaybackFailureKind.TRANSIENT_NETWORK,
            PlaybackFailure.classify(SocketException("Connection reset")),
        )
    }

    @Test
    fun `读超时算瞬时网络类`() {
        assertEquals(
            PlaybackFailureKind.TRANSIENT_NETWORK,
            PlaybackFailure.classify(SocketTimeoutException("timeout")),
        )
    }

    @Test
    fun `域名解析失败算瞬时网络类`() {
        assertEquals(
            PlaybackFailureKind.TRANSIENT_NETWORK,
            PlaybackFailure.classify(UnknownHostException("no dns")),
        )
    }

    @Test
    fun `media3 的 IO 错误码段算瞬时网络类`() {
        val ex = PlaybackException(
            "io",
            IOException("io"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )
        assertEquals(
            PlaybackFailureKind.TRANSIENT_NETWORK,
            PlaybackFailure.classify(ex),
        )
    }

    @Test
    fun `404 是内容类不是网络类`() {
        // ⚠️ 这条最关键：InvalidResponseCodeException 也是 IOException，
        // 若先判 IOException，404 会被误判成网络问题 → 无限重连。
        assertEquals(
            PlaybackFailureKind.FATAL,
            PlaybackFailure.classify(httpError(404)),
        )
    }

    @Test
    fun `403 是内容类`() {
        assertEquals(
            PlaybackFailureKind.FATAL,
            PlaybackFailure.classify(httpError(403)),
        )
    }

    @Test
    fun `5xx 是瞬时网络类`() {
        assertEquals(
            PlaybackFailureKind.TRANSIENT_NETWORK,
            PlaybackFailure.classify(httpError(503)),
        )
    }

    @Test
    fun `429 与 408 是瞬时网络类`() {
        assertEquals(
            PlaybackFailureKind.TRANSIENT_NETWORK,
            PlaybackFailure.classify(httpError(429)),
        )
        assertEquals(
            PlaybackFailureKind.TRANSIENT_NETWORK,
            PlaybackFailure.classify(httpError(408)),
        )
    }

    @Test
    fun `解码失败是内容类`() {
        val ex = PlaybackException(
            "decode",
            IllegalStateException("decoder init failed"),
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )
        assertEquals(
            PlaybackFailureKind.FATAL,
            PlaybackFailure.classify(ex),
        )
    }

    @Test
    fun `未知异常是内容类`() {
        assertEquals(
            PlaybackFailureKind.FATAL,
            PlaybackFailure.classify(IllegalArgumentException("nope")),
        )
    }

    @Test
    fun `空异常是内容类`() {
        assertEquals(PlaybackFailureKind.FATAL, PlaybackFailure.classify(null))
    }

    @Test
    fun `cause 链上的 HTTP 状态码要被认出来`() {
        // PlaybackException 包着 InvalidResponseCodeException：
        // 只看最外层会把 404 判成网络类（因为它最终也是 IOException）。
        val ex = PlaybackException(
            "bad status",
            httpError(404),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )
        assertEquals(
            PlaybackFailureKind.FATAL,
            PlaybackFailure.classify(ex),
        )
    }
}
