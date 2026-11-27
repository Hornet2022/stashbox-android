package com.tingxia.audio.data.sync

import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.DistillStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「蒸馏完成 → 写回播放列表快照」的守卫（C3）。
 *
 * ## 为什么锁它
 *
 * 内测反馈：「蒸馏中的内容加进播放列表，**蒸馏完成后**还是无法播放，提示没有音频」。
 *
 * 根因不是服务端没给地址，而是**没人把地址写回播放列表**：
 * 补齐逻辑挂在 Room 表变化上，而"服务端蒸馏完成"不写本机表 ⇒ 永远不触发。
 *
 * 写错的两个方向，后果都很具体：
 * · **该写不写** ⇒ 用户永远看到"没有音频"（就是这次的内测反馈）；
 * · **不该写也写** ⇒ 播放列表里冒出用户从没加过的条目（幽灵条目）。
 */
class PlaylistSnapshotSyncTest {

    private fun article(
        id: String = "art_1",
        audioUrl: String? = "https://example.com/a.mp3",
        durationSec: Int? = 300,
        title: String? = "测试文章",
        status: DistillStatus = DistillStatus.READY,
    ) = Article(
        id = id,
        title = title,
        status = status,
        audioUrl = audioUrl,
        durationSec = durationSec,
    )

    @Test
    fun `就绪且在播放列表里_必须把地址写回快照`() = runTest {
        var writtenId: String? = null
        var writtenUrl: String? = null
        var writtenDuration: Int? = null

        val ok = PlaylistSnapshotSync.sync(
            article = article(),
            inPlaylist = { true },
            write = { id, url, _, duration ->
                writtenId = id
                writtenUrl = url
                writtenDuration = duration
            },
        )

        assertTrue("就绪且在列表里必须写回", ok)
        assertEquals("art_1", writtenId)
        assertEquals("https://example.com/a.mp3", writtenUrl)
        assertEquals(300, writtenDuration)
    }

    @Test
    fun `不在播放列表里_不许写回_否则造出幽灵条目`() = runTest {
        var writeCalled = false

        val ok = PlaylistSnapshotSync.sync(
            article = article(id = "art_not_mine"),
            inPlaylist = { false },
            write = { _, _, _, _ -> writeCalled = true },
        )

        assertFalse("不在列表里不该写", ok)
        assertFalse("不该调用写回", writeCalled)
    }

    @Test
    fun `地址为空_不许写回_写了也等于没写`() = runTest {
        var writeCalled = false

        val ok = PlaylistSnapshotSync.sync(
            article = article(audioUrl = null),
            inPlaylist = { true },
            write = { _, _, _, _ -> writeCalled = true },
        )

        assertFalse("空地址不该写", ok)
        assertFalse("不该调用写回", writeCalled)
    }

    @Test
    fun `已就绪但地址是空串_同样不许写回`() = runTest {
        var writeCalled = false

        val ok = PlaylistSnapshotSync.sync(
            article = article(audioUrl = "   "),
            inPlaylist = { true },
            write = { _, _, _, _ -> writeCalled = true },
        )

        assertFalse("空白地址不该写", ok)
        assertFalse("不该调用写回", writeCalled)
    }

    @Test
    fun `写回时用的必须是文章自己的地址_不是别处的`() = runTest {
        var writtenUrl: String? = null

        PlaylistSnapshotSync.sync(
            article = article(id = "art_x", audioUrl = "https://example.com/x.mp3"),
            inPlaylist = { true },
            write = { _, url, _, _ -> writtenUrl = url },
        )

        assertEquals("https://example.com/x.mp3", writtenUrl)
    }

    @Test
    fun `写回时标题要一起带过去_不能丢`() = runTest {
        var writtenTitle: String? = null

        PlaylistSnapshotSync.sync(
            article = article(title = "标题在这"),
            inPlaylist = { true },
            write = { _, _, title, _ -> writtenTitle = title },
        )

        assertEquals("标题在这", writtenTitle)
    }
}
