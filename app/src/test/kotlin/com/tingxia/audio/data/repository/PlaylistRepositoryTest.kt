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

    /**
     * 值没变就**不写**（2026-10-23）。
     *
     * 这不是「省一次 IO」的优化，是断一个**死循环**：
     * 播放列表的 observer 在表变化时回调 → 回调里跑补齐逻辑 → 补齐逻辑对
     * 「缺地址/缺时长」的条目调 refreshUrl → 若无条件 upsert → Room 又 emit
     * → observer 又回调 → …… 而「文章还没蒸馏好」时 audioUrl 本来就是 null，
     * 补不上 → 下一轮又挑中它 → **永远停不下来**。
     *
     * 每轮循环都会调一次 syncPlayerPlaylist（动播放器的列表），
     * 所以这个循环不只是白烧 CPU。
     */
    @Test
    fun `refreshUrl_值没变就不写`() = runTest {
        val (dao, repo) = repo()
        repo.add(
            articleId = "a",
            title = "标题 A",
            audioUrl = "https://example.com/a.mp3",
            durationSec = 317,
        )
        val before = dao.items

        // 完全相同的值 —— 不该产生任何写入
        repo.refreshUrl(
            articleId = "a",
            audioUrl = "https://example.com/a.mp3",
            title = "标题 A",
            durationSec = 317,
        )
        assertEquals("值没变却写了表 → 会触发 observer → 死循环", before, dao.items)

        // 值确实变了 —— 必须写
        repo.refreshUrl(articleId = "a", audioUrl = "https://example.com/a2.mp3")
        assertEquals("https://example.com/a2.mp3", dao.items.single().audioUrl)
    }

    @Test
    fun `refreshUrl_补不上地址时也不会反复写`() = runTest {
        val (dao, repo) = repo()
        repo.add(articleId = "a", title = "标题 A")  // 还没蒸馏好：地址/时长都空
        val before = dao.items

        // 上游也拿不到地址（文章还没好）→ 补齐逻辑每轮都会调它
        repo.refreshUrl(articleId = "a", audioUrl = null, durationSec = null)
        assertEquals("补不上就不该写（否则 observer 无限循环）", before, dao.items)
    }
}
