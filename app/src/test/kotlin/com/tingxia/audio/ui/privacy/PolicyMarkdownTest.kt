package com.tingxia.audio.ui.privacy

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 政策正文的 Markdown 渲染单测（2026-10-21）。
 *
 * ## 为什么必须有这个测试
 *
 * 政策正文是**服务端下发的**（`backend/common/policies/privacy_policy.md`），
 * 客户端这边改坏了渲染，只有真机看得到 —— 而"用户读的隐私政策显示成
 * 一堆 `**` 和 `|`"是会被当成合规事故的。
 *
 * 所以"标记有没有被正确剥掉"必须有测试兜着。
 */
class PolicyMarkdownTest {

    @Test
    fun `标题被识别成标题并剥掉井号`() {
        val blocks = PolicyMarkdown.parse("# 听匣隐私政策\n\n## 一、我们收集哪些信息\n\n### 1.1 账号信息")

        assertEquals(3, blocks.size)
        assertEquals(PolicyBlockKind.HEADING, blocks[0].kind)
        assertEquals(1, blocks[0].level)
        assertEquals("听匣隐私政策", blocks[0].text.text)
        assertEquals(2, blocks[1].level)
        assertEquals("一、我们收集哪些信息", blocks[1].text.text)
        assertEquals(3, blocks[2].level)
    }

    @Test
    fun `加粗被剥成加粗片段而不是字面星号`() {
        val blocks = PolicyMarkdown.parse("我们**不**收集通讯录")

        assertEquals(1, blocks.size)
        assertEquals("我们不收集通讯录", blocks[0].text.text)
        assertTrue(
            "「不」必须是加粗片段",
            blocks[0].text.spanStyles.any { it.item.fontWeight == FontWeight.Bold },
        )
    }

    @Test
    fun `斜体被剥成斜体片段`() {
        val blocks = PolicyMarkdown.parse("这是*强调*内容")

        assertEquals("这是强调内容", blocks[0].text.text)
        assertTrue(
            "必须是斜体片段",
            blocks[0].text.spanStyles.any { it.item.fontStyle == FontStyle.Italic },
        )
    }

    @Test
    fun `列表项被识别且符号被剥掉`() {
        val blocks = PolicyMarkdown.parse("- 第一条\n- 第二条\n\n1. 有序第一条\n2. 有序第二条")

        assertEquals(4, blocks.size)
        assertEquals(PolicyBlockKind.BULLET, blocks[0].kind)
        assertEquals("第一条", blocks[0].text.text)
        assertEquals(PolicyBlockKind.BULLET, blocks[1].kind)
        assertEquals(PolicyBlockKind.ORDERED, blocks[2].kind)
        assertEquals(1, blocks[2].order)
        assertEquals("有序第一条", blocks[2].text.text)
        assertEquals(2, blocks[3].order)
    }

    @Test
    fun `链接只显示文字并附上地址`() {
        val blocks = PolicyMarkdown.parse("详见 [隐私政策](https://stashbox.cn/privacy)。")

        assertEquals(1, blocks.size)
        assertTrue(
            "链接文字必须保留：${blocks[0].text.text}",
            blocks[0].text.text.contains("隐私政策"),
        )
        assertTrue(
            "地址必须留下（用户要能去核对）：${blocks[0].text.text}",
            blocks[0].text.text.contains("https://stashbox.cn/privacy"),
        )
        assertTrue(
            "Markdown 的方括号与圆括号不该出现在正文里",
            !blocks[0].text.text.contains("]("),
        )
    }

    @Test
    fun `分隔线被识别`() {
        val blocks = PolicyMarkdown.parse("上文\n\n---\n\n下文")

        assertEquals(3, blocks.size)
        assertEquals(PolicyBlockKind.RULE, blocks[1].kind)
    }

    @Test
    fun `表格被渲染成表头对值的成对文本`() {
        val blocks = PolicyMarkdown.parse(
            """
            | 目的 | 涉及的信息 |
            |---|---|
            | 生成音频 | 剪藏的链接与正文 |
            | 保持登录 | OpenID、播放进度 |
            """.trimIndent(),
        )

        assertEquals(2, blocks.size)
        assertEquals(PolicyBlockKind.TABLE_ROW, blocks[0].kind)
        assertEquals("目的：生成音频；涉及的信息：剪藏的链接与正文", blocks[0].text.text)
        assertEquals("目的：保持登录；涉及的信息：OpenID、播放进度", blocks[1].text.text)
    }

    @Test
    fun `没配对的标记原样留下而不是吞掉内容`() {
        val blocks = PolicyMarkdown.parse("这里有个**没闭合的加粗")

        assertEquals(1, blocks.size)
        assertEquals("这里有个**没闭合的加粗", blocks[0].text.text)
    }

    @Test
    fun `真实政策正文的骨架能被解析`() {
        // 服务端政策正文里的结构：标题、加粗、列表、表格、分隔线。
        val markdown = """
            # 听匣隐私政策

            **版本：v1　最后更新：2026-10-21**

            听匣是一款把文章蒸馏成音频的应用。

            ---

            ## 一、我们收集哪些信息

            - **剪藏的链接与正文**：我们会抓取该页面的标题与正文。
            - **播放进度**：用于断点续听。

            | 数据 | 保存期限 |
            |---|---|
            | 账号信息 | 注销时立即匿名化 |
        """.trimIndent()

        val blocks = PolicyMarkdown.parse(markdown)

        assertTrue("应该有标题", blocks.any { it.kind == PolicyBlockKind.HEADING })
        assertTrue("应该有列表", blocks.any { it.kind == PolicyBlockKind.BULLET })
        assertTrue("应该有表格行", blocks.any { it.kind == PolicyBlockKind.TABLE_ROW })
        assertTrue("应该有分隔线", blocks.any { it.kind == PolicyBlockKind.RULE })
        assertTrue(
            "正文里不该残留 Markdown 的加粗标记",
            blocks.none { it.text.text.contains("**") },
        )
    }
}
