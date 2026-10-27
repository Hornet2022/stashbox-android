package com.tingxia.audio.ui.articles

import androidx.media3.exoplayer.ExoPlayer
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.data.FakeArticleApi
import com.tingxia.audio.data.FakeDistillationApi
import com.tingxia.audio.data.FakeProgressApi
import com.tingxia.audio.data.FakeTagApi
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.remote.ProgressGetResponse
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.data.repository.EvaluationRepository
import com.tingxia.audio.data.repository.ProgressRepository
import com.tingxia.audio.data.repository.TagRepository
import com.tingxia.audio.data.local.FakePlaylistDao
import com.tingxia.audio.data.local.InMemoryPlaybackProgressDao
import com.tingxia.audio.data.repository.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「已听完的剪藏再次打开」的交互（2026-10-23 新增）。
 *
 * ## 真机上发生了什么
 *
 * 已经播放完的剪藏内容再次打开时：
 * - 断点续听把进度恢复到**末尾** → 进度条一上来就在最右端；
 * - 立刻播完 → 完听判定成立 → **评价弹窗直接糊在脸上**。
 *
 * 用户只是想再打开看看，却被当成「又听了一遍」索评。
 *
 * ## 现在的规则
 *
 * - 服务端已标 `listened`，**或**断点位置已贴着结尾（≥95% 时长）
 *   → 判定为「已听完」→ **从头开始播**，不恢复到末尾。
 * - 没听完 → 照旧断点续播。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArticleDetailResumeBehaviorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var playerController: PlayerController

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        playerController = PlayerController(
            ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build(),
            RuntimeEnvironment.getApplication(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeVm(
        article: Article,
        positionSec: Int?,
    ): ArticleDetailViewModel {
        val api = FakeArticleApi().apply {
            this.articles = listOf(article)
            this.audioUrls = mapOf(article.id to "https://example.com/${article.id}.mp3")
            this.statuses = mapOf(article.id to article.status)
        }
        val progressGet = ProgressGetResponse(
            article_id = article.id,
            position_sec = positionSec,
            total_sec = article.durationSec,
        )
        return ArticleDetailViewModel(
            repository = ArticleRepository(api),
            progressRepository = ProgressRepository(
                FakeProgressApi(progressGet, updateOk = true),
                InMemoryPlaybackProgressDao(),
            ),
            progressApi = FakeProgressApi(progressGet, true),
            playerController = playerController,
            evaluationRepository = EvaluationRepository(FakeDistillationApi()),
            playlistRepository = PlaylistRepository(FakePlaylistDao()),
            tagRepository = TagRepository(FakeTagApi()),
            context = RuntimeEnvironment.getApplication(),
        )
    }

    @Test
    fun `已听完的文章再次打开时从头播而不是拉到末尾`() = testScope.runTest {
        val article = Article(
            id = "a",
            title = "t",
            status = DistillStatus.LISTENED,
            audioUrl = "https://example.com/a.mp3",
            taskId = "t1",
            durationSec = 317,
        )
        val vm = fakeVm(article, positionSec = 316)
        vm.loadArticle("a")
        testScheduler.advanceUntilIdle()

        assertEquals(
            "已听完的文章应该从头播（位置保持 0），不该恢复到末尾",
            0L,
            playerController.position.value,
        )
    }

    @Test
    fun `没听完的文章照旧断点续播`() = testScope.runTest {
        val article = Article(
            id = "a",
            title = "t",
            status = DistillStatus.READY,
            audioUrl = "https://example.com/a.mp3",
            taskId = "t1",
            durationSec = 317,
        )
        val vm = fakeVm(article, positionSec = 100)
        vm.loadArticle("a")
        testScheduler.advanceUntilIdle()

        assertEquals(
            "没听完的必须断点续播",
            100_000L,
            playerController.position.value,
        )
    }

    @Test
    fun `断点已贴着结尾也算听完并从头播`() = testScope.runTest {
        // 状态还是 ready（服务端没标 listened），但断点已经在 99% 处
        val article = Article(
            id = "a",
            title = "t",
            status = DistillStatus.READY,
            audioUrl = "https://example.com/a.mp3",
            taskId = "t1",
            durationSec = 317,
        )
        val vm = fakeVm(article, positionSec = 315)
        vm.loadArticle("a")
        testScheduler.advanceUntilIdle()

        assertEquals(
            "断点已贴着结尾的也要从头播",
            0L,
            playerController.position.value,
        )
    }

    @Test
    fun `评分读回完成后才允许自动弹评分卡`() = testScope.runTest {
        val article = Article(
            id = "a",
            title = "t",
            status = DistillStatus.READY,
            audioUrl = "https://example.com/a.mp3",
            taskId = "t1",
            durationSec = 317,
        )
        val vm = fakeVm(article, positionSec = null)
        vm.loadArticle("a")
        testScheduler.advanceUntilIdle()

        assertTrue(
            "读回结束后 shouldShowEvaluationDialog 的门槛条件才成立",
            vm.ratingLookupDone.value,
        )
    }
}
