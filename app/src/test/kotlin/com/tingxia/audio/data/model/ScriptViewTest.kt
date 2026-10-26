package com.tingxia.audio.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP-TITLE-ANNOUNCE（2026-10-26）：标题播报后，稿子的展示判定。
 *
 * ## 为什么这组判定要有单测
 *
 * 服务端给音频开头加了一句标题播报（"本期分享的标题是：…"），
 * 这句话为了跟音频对齐也进了 `script_text`。
 *
 * 于是一旦**客户端与服务端对不齐**，就会静默地错位：
 * · 导语变成「本期分享的标题是：…」——正文的第一段被顶掉，用户看到的是一句口播；
 * · 或者播报句在详情页被当正文显示；
 * · 或者分区渲染把中间的正文段落整段吃掉。
 *
 * 这三种错**App 都不报错**，只是"看起来怪"。所以判据必须被单测钉住。
 */
class ScriptViewTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parts(
        hook: String? = null,
        outro: String? = null,
        intro: String? = null,
        sections: List<String> = listOf("正文要点"),
    ) = ContentParts(
        sections = sections,
        insights = listOf("延伸判断"),
        related = listOf("关联思考"),
        followUps = listOf("拓展方向"),
        hook = hook,
        outro = outro,
        intro = intro,
    )

    @Test
    fun `播报句不当正文_导语用服务端给的 hook`() {
        val announcement = "本期分享的标题是：为什么需要 Ontology。"
        val script = "$announcement\n\n今天聊聊企业为什么需要本体。\n\n第一段正文。\n\n就聊到这。"

        val view = resolveScriptView(
            script,
            parts(hook = "今天聊聊企业为什么需要本体。", outro = "就聊到这。", intro = announcement),
        )!!

        assertEquals("今天聊聊企业为什么需要本体。", view.hook)
        assertEquals("就聊到这。", view.outro)
        assertFalse("播报句不能留在正文里", view.bodyParagraphs.contains(announcement))
        assertEquals(3, view.bodyParagraphs.size)
    }

    @Test
    fun `没有 content_parts 时回退到首段当导语`() {
        val script = "今天聊聊企业为什么需要本体。\n\n第一段正文。\n\n就聊到这。"

        val view = resolveScriptView(script, null)!!

        assertEquals("今天聊聊企业为什么需要本体。", view.hook)
        assertEquals("就聊到这。", view.outro)
        assertEquals(3, view.bodyParagraphs.size)
        assertFalse("没有分区就平铺渲染", view.hasParts)
    }

    @Test
    fun `存量数据有分区但没有 hook 键时仍回退首段`() {
        // 老数据的 content_parts 只有四个列表，没有 hook/outro/intro。
        val legacy = ContentParts(
            sections = listOf("正文要点"),
            insights = listOf("延伸判断"),
            related = listOf("关联思考"),
            followUps = listOf("拓展方向"),
        )
        val script = "今天聊聊企业为什么需要本体。\n\n第一段正文。\n\n就聊到这。"

        val view = resolveScriptView(script, legacy)!!

        assertEquals("今天聊聊企业为什么需要本体。", view.hook)
        assertTrue("有分区就走分区渲染", view.hasParts)
    }

    @Test
    fun `分区列表全空时不能走分区渲染`() {
        // 分区渲染只画「四类 + hook + outro」—— 若分区全空却走分区渲染，
        // 中间的正文段落会整段消失。
        val empty = ContentParts(
            sections = emptyList(),
            insights = emptyList(),
            related = emptyList(),
            followUps = emptyList(),
            hook = "今天聊聊企业为什么需要本体。",
            outro = "就聊到这。",
            intro = "本期分享的标题是：标题。",
        )
        val script = "本期分享的标题是：标题。\n\n今天聊聊企业为什么需要本体。\n\n第一段正文。\n\n就聊到这。"

        val view = resolveScriptView(script, empty)!!

        assertFalse("分区全空必须平铺，否则正文会消失", view.hasParts)
        assertEquals(3, view.bodyParagraphs.size)
    }

    @Test
    fun `只有一段时没有 outro`() {
        val view = resolveScriptView("只有一段。", null)!!

        assertEquals("只有一段。", view.hook)
        assertNull("单段没有收束段", view.outro)
    }

    @Test
    fun `空稿件返回 null`() {
        assertNull(resolveScriptView(null, null))
        assertNull(resolveScriptView("", null))
        assertNull(resolveScriptView("   \n\n  ", null))
    }

    @Test
    fun `服务端加的三个键能被解析且老数据不受影响`() {
        // 新数据：三个键都在
        val newData = json.decodeFromString<ContentParts>(
            """{"sections":["a"],"insights":[],"related":[],"follow_ups":[],
               "hook":"钩子","outro":"收束","intro":"本期分享的标题是：标题。"}""",
        )
        assertEquals("钩子", newData.hook)
        assertEquals("收束", newData.outro)
        assertEquals("本期分享的标题是：标题。", newData.intro)

        // 老数据：没有这三个键 —— 必须能解析（缺失即 null），不能抛
        val oldData = json.decodeFromString<ContentParts>(
            """{"sections":["a"],"insights":[],"related":[],"follow_ups":[]}""",
        )
        assertNull(oldData.hook)
        assertNull(oldData.outro)
        assertNull(oldData.intro)
    }
}
