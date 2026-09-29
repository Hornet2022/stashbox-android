package com.tingxia.audio.ui.components

import com.tingxia.audio.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * source 原始码 → 文案映射的回归。
 *
 * 真机踩过：文章列表副标题直接显示 `article.source`，真机上露出
 * "unknown" / "d9" 这种机器码；播放器还把它当"作者"塞进 MediaSession，
 * 通知栏也跟着显示 unknown。
 *
 * `articles.source` 的真实分布（2026-09 抽样 686 条）：
 * d9 509 / wechat 91 / wechat_mp 46 / douyin 26 / pdf 13 / unknown 1。
 * 所以只认 wechat + douyin 的老映射会把绝大多数条目打到 else。
 */
class SourceLabelTest {

    @Test
    fun `已知的 source 码各自映射到正确文案`() {
        assertEquals(R.string.source_wechat, sourceLabelRes("wechat"))
        assertEquals(R.string.source_wechat_mp, sourceLabelRes("wechat_mp"))
        assertEquals(R.string.source_douyin, sourceLabelRes("douyin"))
        assertEquals(R.string.source_d9, sourceLabelRes("d9"))
        assertEquals(R.string.source_browser, sourceLabelRes("browser"))
        assertEquals(R.string.source_share, sourceLabelRes("share"))
        assertEquals(R.string.source_pdf, sourceLabelRes("pdf"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(R.string.source_wechat, sourceLabelRes("WeChat"))
        assertEquals(R.string.source_wechat_mp, sourceLabelRes("WECHAT_MP"))
    }

    @Test
    fun `未知值和 null 落到通用而不是漏出机器码`() {
        assertEquals(R.string.source_generic, sourceLabelRes("unknown"))
        assertEquals(R.string.source_generic, sourceLabelRes(null))
        // 未来新增的码也不能直接把原文打到 UI 上
        assertEquals(R.string.source_generic, sourceLabelRes("some_future_source"))
        assertEquals(R.string.source_generic, sourceLabelRes(""))
    }

    @Test
    fun `d9 不再被当成未知值`() {
        // d9 是分享导入入口，占真实数据一半以上；老实现把它和其他杂码一起
        // 归到 else，徽章显示"通用"、副标题显示 "d9"
        assertEquals(R.string.source_d9, sourceLabelRes("d9"))
    }
}
