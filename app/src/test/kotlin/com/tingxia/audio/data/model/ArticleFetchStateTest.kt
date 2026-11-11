package com.tingxia.audio.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Article] 的**失败原因**与**抓取中**两个字段（2026-11-11）。
 *
 * ## 为什么要有这个测试
 *
 * 剪藏改成**后台抓取**之后：
 * · 抓取失败不再在 HTTP 响应里当场返回，而是落成 `status=failed` +
 *   `articles.error`（面向用户的中文原因）；
 * · 抓取进行中，服务端会带 `fetch_pending=true`。
 *
 * 这两个字段是客户端**唯一**能告诉用户「为什么失败 / 还在动」的依据。
 * 如果字段名写错（比如后端叫 `fetch_pending`、客户端写成 `fetchPending`
 * 而没加 `@SerialName`），序列化会**静默**丢字段 ⇒ 客户端永远显示不出原因，
 * 而且没有任何编译错误 —— 所以必须钉住「服务端给什么，客户端读得到什么」。
 */
class ArticleFetchStateTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `失败原因能读出来`() {
        val article = json.decodeFromString<Article>(
            """{"id":"art_1","url":"https://x.com/a","status":"failed",
               "error":"没能连上这个网站，可能是网络不通或对方站点暂时不可用，请稍后重试"}""",
        )
        assertEquals("failed", article.status.name.lowercase())
        assertEquals(
            "没能连上这个网站，可能是网络不通或对方站点暂时不可用，请稍后重试",
            article.error,
        )
    }

    @Test
    fun `抓取中能读出来`() {
        val article = json.decodeFromString<Article>(
            """{"id":"art_2","url":"https://x.com/b","status":"pending","fetch_pending":true}""",
        )
        assertTrue(article.fetchPending)
    }

    @Test
    fun `老服务端没有这两个字段也能解析`() {
        // 兼容：0.5.3 及更早的服务端不返回 error / fetch_pending
        val article = json.decodeFromString<Article>(
            """{"id":"art_3","url":"https://x.com/c","status":"ready"}""",
        )
        assertNull(article.error)
        assertFalse(article.fetchPending)
    }
}
