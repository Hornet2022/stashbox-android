package com.tingxia.audio.ui

import com.tingxia.audio.data.remote.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪藏抓取失败（2001/2002）的文案必须**原样透传后端**，不能被压成
 * "服务暂不可用，请稍后再试"。
 *
 * 2026-10-03：后端改造前，抓取失败被 content-service 软降级吞掉，接口返回 200，
 * 用户等 12 分钟才看到失败。改造后抓取失败当场返回 2001/2002，message 是
 * 特意写给用户看的中文（网络不通 / 微信要风控 / 文章已删 / 对方限流）。
 *
 * 客户端这一层如果再概括成"服务暂不可用"，等于把刚做好的区分又抹平了：
 * 用户看到的是"我们挂了"，实际是"对方网站连不上"，该做的动作完全不同。
 */
class CaptureFetchErrorMessagesTest {

    private fun apiEx(bizCode: Int, httpCode: Int, message: String) =
        ApiException(httpCode = httpCode, bizCode = bizCode, message = message)

    @Test
    fun `2001 不支持的来源 用后端文案而不是泛化 400`() {
        val msg = friendlyError(
            apiEx(2001, 400, "暂不支持这个链接来源，换个网站再试试"),
            fallback = "剪藏失败，请稍后再试",
        )
        assertEquals("暂不支持这个链接来源，换个网站再试试", msg)
        assertTrue("不能退化成 400 泛化文案", msg != "请求失败（400）")
    }

    @Test
    fun `2002 微信风控 保留可操作提示`() {
        val backend = "对方网站限制了非官方客户端访问，微信文章请在微信里打开后重新复制链接"
        val msg = friendlyError(apiEx(2002, 502, backend), fallback = "剪藏失败，请稍后再试")
        assertEquals(backend, msg)
        // 关键：502 以前会落进 in 500..599 分支变成"服务暂不可用"
        assertTrue("不能被压成服务不可用", msg != "服务暂不可用，请稍后再试")
    }

    @Test
    fun `2002 各种抓取原因都原样透传`() {
        val reasons = listOf(
            "没能连上这个网站，可能是网络不通或对方站点暂时不可用，请稍后重试",
            "对方网站暂时限制了访问，请过几分钟再试",
            "这篇文章已被删除或链接已失效",
            "没能提取出文章正文，页面可能需要登录后才能查看",
        )
        for (r in reasons) {
            assertEquals(r, friendlyError(apiEx(2002, 502, r), fallback = "兜底"))
        }
    }

    @Test
    fun `2002 message 为空时用兜底而不是空串`() {
        val msg = friendlyError(apiEx(2002, 502, ""), fallback = "兜底")
        assertEquals("抓取失败，请稍后重试", msg)
    }

    @Test
    fun `其他 5xx 仍走原有的服务不可用文案`() {
        val msg = friendlyError(apiEx(40000, 500, "内部错误"), fallback = "兜底")
        assertEquals("服务暂不可用，请稍后再试", msg)
    }

    @Test
    fun `配额用尽的判定不受影响`() {
        val msg = friendlyError(apiEx(3001, 403, "Quota exceeded"), fallback = "兜底")
        assertEquals("今日配额已用完，开通会员继续收听", msg)
    }
}
