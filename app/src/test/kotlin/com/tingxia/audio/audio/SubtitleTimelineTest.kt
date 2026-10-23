package com.tingxia.audio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字幕时间轴的计算（2026-10-23）。
 *
 * 这是一条**纯计算**链路（听感稿 + 音频时长 → 若干条 cue），
 * 所以边界都能用单测钉死，不需要真机。
 *
 * 真正要紧的是这几条：
 * - 空稿 / 零时长 → 没有字幕（而不是崩、或者一条空 cue）
 * - 时间轴必须**铺满**整段音频（最后一条的结束 = 总时长）——
 *   否则播到结尾字幕会突然消失
 * - 只有标点没有字 → 没有字幕（标点不发音）
 * - 超长无标点段落 → 必须切得开（否则一整屏字）
 * - 查某一时刻的字幕 → 边界要闭合（落在区间内能查到）
 */
class SubtitleTimelineTest {

    @Test
    fun `空稿没有字幕`() {
        assertTrue(SubtitleTimeline.from(null, 60_000L).cues.isEmpty())
        assertTrue(SubtitleTimeline.from("", 60_000L).cues.isEmpty())
        assertTrue(SubtitleTimeline.from("   ", 60_000L).cues.isEmpty())
    }

    @Test
    fun `零时长没有字幕`() {
        assertTrue(SubtitleTimeline.from("这是正文。", 0L).cues.isEmpty())
        assertTrue(SubtitleTimeline.from("这是正文。", -1L).cues.isEmpty())
    }

    @Test
    fun `只有标点没有字幕`() {
        // 标点不发音 → 算不出任何时间
        assertTrue(SubtitleTimeline.from("。。。！！！？？？", 60_000L).cues.isEmpty())
    }

    @Test
    fun `按句切分`() {
        val timeline = SubtitleTimeline.from("第一句话。第二句话！第三句话？", 30_000L)
        assertEquals(3, timeline.cues.size)
        assertEquals("第一句话。", timeline.cues[0].text)
        assertEquals("第二句话！", timeline.cues[1].text)
        assertEquals("第三句话？", timeline.cues[2].text)
    }

    @Test
    fun `时间轴铺满整段音频`() {
        val timeline = SubtitleTimeline.from("第一句话内容比较长。第二句话也不短。第三句话同样。", 30_000L)
        val last = timeline.cues.last()
        assertEquals(
            "最后一条必须结束在总时长上，否则播到结尾字幕会凭空消失",
            30_000L, last.endMs,
        )
        assertTrue("第一条必须从 0 开始", timeline.cues.first().startMs == 0L)
    }

    @Test
    fun `各条首尾相接不留缝`() {
        val timeline = SubtitleTimeline.from("一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十。", 60_000L)
        for (i in 0 until timeline.cues.size - 1) {
            assertEquals(
                "第 ${i + 1} 条与第 ${i + 2} 条之间不能有缝",
                timeline.cues[i].endMs, timeline.cues[i + 1].startMs,
            )
        }
    }

    @Test
    fun `超长无标点段落会切得开`() {
        val long = "这".repeat(200)
        val timeline = SubtitleTimeline.from(long, 60_000L)
        assertTrue("200 字无标点必须切成多条", timeline.cues.size > 1)
        for (cue in timeline.cues) {
            assertTrue("单条不能太长：${cue.text.length}", cue.text.length <= 40)
        }
    }

    @Test
    fun `查某一时刻能查到对应的句子`() {
        val timeline = SubtitleTimeline.from("第一句话。第二句话。第三句话。", 30_000L)
        val cue = timeline.cueAt(1_000L)
        assertNotNull(cue)
        assertEquals("第一句话。", cue!!.text)
        // 正好播到结尾：最后一句还留着（音频刚结束，眼睛还在那一句上）
        assertEquals("第三句话。", timeline.cueAt(30_000L)?.text)
        // 过了结尾就不要再显示
        assertNull(timeline.cueAt(30_001L))
    }

    @Test
    fun `带标点的稿子按说话字数分配时间`() {
        // 第一句 3 个字 + 1 个句号，第二句 3 个字 + 1 个句号
        // → 时间应当大致对半分
        val timeline = SubtitleTimeline.from("一二三。四五六。", 10_000L)
        assertEquals(2, timeline.cues.size)
        val first = timeline.cues[0]
        val second = timeline.cues[1]
        assertTrue("第一句应占约一半：${first.endMs}", first.endMs in 4_000L..6_000L)
        assertEquals(10_000L, second.endMs)
    }
}
