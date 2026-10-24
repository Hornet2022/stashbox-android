package com.tingxia.audio.ui.components

import com.tingxia.audio.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `github 与 document 不再显示为通用`() {
        // 2026-10-23：新增了 GitHub 开源库剪藏与文档剪藏两个来源码。
        // 不加映射的话它们会落到 else → 徽章显示「通用」，
        // 用户剪了一个开源库、上传了一份文档，列表里却看不出剪的是什么。
        assertEquals(R.string.source_github, sourceLabelRes("github"))
        assertEquals(R.string.source_document, sourceLabelRes("document"))
        // 大小写也要认（sourceLabelRes 内部会 lowercase）
        assertEquals(R.string.source_github, sourceLabelRes("GitHub"))
        assertEquals(R.string.source_document, sourceLabelRes("Document"))
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
    fun `徽章的文案必须走唯一映射出口_不能自己再写一份`() {
        // 2026-10-24 实测过的漂移：SourceBadge 里自己写了一份 when（只认
        // wechat / douyin），于是同一篇文章**首页副标题**显示「GitHub」「文档」，
        // 而**徽章**显示「通用」—— 同一个 App 里同一个东西的两种说法。
        //
        // 护栏：徽章的文案必须来自 sourceLabelRes（模块 docstring 写明它是
        // 「唯一映射出口」）。这里断言它**没有**自己的文案映射。
        // 单元测试的 CWD 不一定是模块根（可能是 android/ 或 android/app/），
        // 所以从当前目录**逐级向上**找源文件。
        val rel = "app/src/main/kotlin/com/tingxia/audio/ui/components/SourceBadge.kt"
        var dir: java.io.File? = java.io.File(".").absoluteFile
        var found: java.io.File? = null
        while (dir != null && found == null) {
            val f = java.io.File(dir, rel)
            if (f.exists()) found = f
            dir = dir.parentFile
        }
        val src = found?.readText(Charsets.UTF_8) ?: ""
        val badgeBody = src.substringAfter("fun SourceBadge(")
        assertTrue(
            "SourceBadge 必须用 sourceLabelRes 取文案，不能自己再写一份映射",
            badgeBody.contains("sourceLabelRes(source)"),
        )
        assertFalse(
            "SourceBadge 里不许再出现文案映射（source_generic / source_wechat 等）",
            badgeBody.contains("R.string.source_"),
        )
    }

    @Test
    fun `d9 不再被当成未知值`() {
        // d9 是分享导入入口，占真实数据一半以上；老实现把它和其他杂码一起
        // 归到 else，徽章显示"通用"、副标题显示 "d9"
        assertEquals(R.string.source_d9, sourceLabelRes("d9"))
    }
}
