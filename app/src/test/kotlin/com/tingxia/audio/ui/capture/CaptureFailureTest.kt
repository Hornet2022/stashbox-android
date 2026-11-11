package com.tingxia.audio.ui.capture

import com.tingxia.audio.data.model.ArticleByUrlResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 剪藏失败后「到底怎么了」的判定（2026-11-11）。
 *
 * ## 这一层值得单独测
 *
 * 剪藏是**同步长请求**（服务端当场抓取，几秒到几十秒才返回），
 * 客户端在等响应时任何抖动都会变成「**服务端成功、客户端报错**」。
 *
 * 实测（uid 23，2026-11-11 17:40~17:43）：4 次剪藏服务端**全部 200**、
 * 文章**全部建好并进入蒸馏**，客户端却弹了「网络不给力」——
 * 用户以为 4 次都失败了，重启 App 才发现文章都在。
 *
 * ⇒ 三种结局必须分清楚：
 *   · 服务端说"我收过了" ⇒ **其实成功**（不能报失败）
 *   · 服务端说"没收到"   ⇒ 真失败
 *   · 连问都问不到       ⇒ **不知道**（不能替用户下结论）
 */
class CaptureFailureTest {

    private val url = "https://mp.weixin.qq.com/s/abc"

    @Test
    fun `服务端其实收过了就判成功而不是失败`() = runTest {
        val result = CaptureFailure.resolve(
            url = url,
            error = SocketTimeoutException("响应没收到"),
            lookup = { ArticleByUrlResponse(exists = true, articleId = "art_x", status = "distilling") },
        )
        assertEquals(CaptureFailure.Outcome.SUCCEEDED, result.outcome)
        assertEquals("art_x", result.articleId)
        assertEquals("actually_succeeded", result.recheck)
        // 成功就不该给用户任何错误文案
        assertNull(result.message)
    }

    @Test
    fun `服务端说没收过就判失败并给文案`() = runTest {
        val result = CaptureFailure.resolve(
            url = url,
            error = SocketTimeoutException("连接断了"),
            lookup = { ArticleByUrlResponse(exists = false) },
        )
        assertEquals(CaptureFailure.Outcome.FAILED, result.outcome)
        assertEquals("not_found", result.recheck)
        assertTrue("失败要有文案", !result.message.isNullOrBlank())
    }

    @Test
    fun `连回查都做不了时判未知而不是失败`() = runTest {
        val result = CaptureFailure.resolve(
            url = url,
            error = SocketTimeoutException("连接断了"),
            lookup = { throw IOException("回查也失败") },
        )
        assertEquals(CaptureFailure.Outcome.UNKNOWN, result.outcome)
        assertEquals("pending", result.recheck)
        // 未知 ⇒ 文案必须说"待确认"，不能说"失败"
        assertEquals(CaptureFailure.UNKNOWN_MESSAGE, result.message)
        assertTrue(
            "未知文案不能断言失败",
            !result.message.orEmpty().contains("失败"),
        )
    }

    @Test
    fun `服务端明确拒绝时不用回查`() = runTest {
        var lookedUp = false
        val result = CaptureFailure.resolve(
            url = url,
            error = retrofit2.HttpException(
                retrofit2.Response.error<Any>(
                    422,
                    "Unprocessable".toResponseBody("text/plain".toMediaType()),
                ),
            ),
            lookup = {
                lookedUp = true
                ArticleByUrlResponse(exists = false)
            },
        )
        assertEquals(CaptureFailure.Outcome.FAILED, result.outcome)
        assertEquals("server_rejected", result.recheck)
        assertTrue("服务端明确拒绝时不该回查", !lookedUp)
    }

    @Test
    fun `失败分类是稳定字符串而不是类名`() = runTest {
        val result = CaptureFailure.resolve(
            url = url,
            error = SocketTimeoutException("t"),
            lookup = { ArticleByUrlResponse(exists = false) },
        )
        // release 包开了 R8 ⇒ 类名会被混淆 ⇒ 不能用 javaClass.simpleName
        assertEquals("socket_timeout", result.kind)
        assertTrue("kind 不该是类名", !result.kind.contains("Exception"))
    }

    @Test
    fun `普通IO异常归为io_error`() = runTest {
        val result = CaptureFailure.resolve(
            url = url,
            error = IOException("断流"),
            lookup = { ArticleByUrlResponse(exists = false) },
        )
        assertEquals("io_error", result.kind)
    }
}
