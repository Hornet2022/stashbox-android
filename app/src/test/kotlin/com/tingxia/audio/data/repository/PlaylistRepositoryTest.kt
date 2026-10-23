package com.tingxia.audio.data.repository

import com.tingxia.audio.data.local.FakePlaylistDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放列表的顺序逻辑（2026-10-23）。
 *
 * 这里锁的是**顺序**的语义 —— 播放列表是队列的唯一真相源，
 * 顺序错了，「上一首/下一首」就跳到错的曲子。
 *
 * 重点在 `position` 必须**稠密**（0..n-1，中间不留洞）：
 * 稀疏的话，「下一条 = position + 1」在删过一条之后就取不到了。
 */
class PlaylistRepositoryTest {

    private fun repo(dao: FakePlaylistDao = FakePlaylistDao()) =
        dao to PlaylistRepository(dao)

    @Test
    fun `加入的顺序就是播放顺序`() = runTest {
        val (_, repo) = repo()
        repo.add("a", title = "A")
        repo.add("b", title = "B")
        repo.add("c", title = "C")

        val items = repo.all()
        assertEquals(listOf("a", "b", "c"), items.map { it.articleId })
        assertEquals(listOf(0, 1, 2), items.map { it.position })
    }

    @Test
    fun `重复加入不改变位置`() = runTest {
        val (_, repo) = repo()
        repo.add("a", title = "A")
        repo.add("b", title = "B")
        repo.add("a", title = "A")   // 再点一次「加入」

        val items = repo.all()
        assertEquals("重复加入不该把这一篇挪到末尾", listOf("a", "b"), items.map { it.articleId })
    }

    @Test
    fun `删除后位置重新稠密`() = runTest {
        val (_, repo) = repo()
        repo.add("a"); repo.add("b"); repo.add("c")
        repo.remove("b")

        val items = repo.all()
        assertEquals(listOf("a", "c"), items.map { it.articleId })
        assertEquals(
            "位置必须重新稠密（0..n-1），否则「下一条 = position+1」取不到",
            listOf(0, 1), items.map { it.position },
        )
    }

    @Test
    fun `上移下移`() = runTest {
        val (_, repo) = repo()
        repo.add("a"); repo.add("b"); repo.add("c")

        assertTrue(repo.moveUp("c"))
        assertEquals(listOf("a", "c", "b"), repo.all().map { it.articleId })
        assertEquals(listOf(0, 1, 2), repo.all().map { it.position })

        assertTrue(repo.moveDown("a"))
        assertEquals(listOf("c", "a", "b"), repo.all().map { it.articleId })
        assertEquals(listOf(0, 1, 2), repo.all().map { it.position })
    }

    @Test
    fun `已经在最前不能上移_在最后不能下移`() = runTest {
        val (_, repo) = repo()
        repo.add("a"); repo.add("b")

        assertFalse("第一个不能再上移", repo.moveUp("a"))
        assertFalse("最后一个不能再下移", repo.moveDown("b"))
        assertEquals(listOf("a", "b"), repo.all().map { it.articleId })
    }

    @Test
    fun `包含判断与清空`() = runTest {
        val (_, repo) = repo()
        repo.add("a")
        assertTrue(repo.contains("a"))
        assertFalse(repo.contains("z"))
        assertEquals(1, repo.count())

        repo.clear()
        assertEquals(0, repo.count())
        assertFalse(repo.contains("a"))
    }

    @Test
    fun `加入时带上元数据快照`() = runTest {
        val (_, repo) = repo()
        repo.add(
            articleId = "a",
            title = "标题 A",
            audioUrl = "https://example.com/a.mp3",
            durationSec = 317,
        )
        val item = repo.all().single()
        assertEquals("标题 A", item.title)
        assertEquals("https://example.com/a.mp3", item.audioUrl)
        assertEquals(317, item.durationSec)
    }
}
