package com.tingxia.audio.ui.evaluation

import com.tingxia.audio.data.FakeDistillationApi
import com.tingxia.audio.data.model.MyEvaluationResponse
import com.tingxia.audio.data.repository.EvaluationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 听感评分**读回**（评分闭环读侧）的回归。
 *
 * 背景：评分一直是"只写不读"——POST 写进 `distillation_evaluations`，
 * 而这张表长期只有 `/api/v1/admin/evaluations` 一个 admin 读入口。
 * 真机 DB 上该表 0 条、界面提交后什么都不剩，这就是"提交评分没有闭环"。
 *
 * 这里锁住读回带来的三个行为：能读出已评分、能预填表单、读回失败不阻断评分。
 */
@OptIn(ExperimentalCoroutinesApi::class)
// RatingPolicy 依赖 DataStore（需要真实 Application context），故跑 Robolectric
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EvaluationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var api: FakeDistillationApi
    private lateinit var policy: RatingPolicy
    private lateinit var vm: EvaluationViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        api = FakeDistillationApi()
        // RatingPolicy 是 @Singleton + DataStore，构造无副作用；本组用例不校验频次
        policy = RatingPolicy(RuntimeEnvironment.getApplication())
        vm = EvaluationViewModel(EvaluationRepository(api), policy)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun run(body: suspend TestScope.() -> Unit) = testScope.runTest { body() }

    @Test
    fun `没评过时 isAlreadyRated 为 false`() = run {
        api.myRating = MyEvaluationResponse(taskId = "dst_1")
        vm.initWithTask("dst_1")
        testScheduler.advanceUntilIdle()

        assertFalse(vm.isAlreadyRated)
    }

    @Test
    fun `读回已评分时 isAlreadyRated 为 true`() = run {
        api.myRating = MyEvaluationResponse(
            id = "eval_1",
            taskId = "dst_1",
            hookScore = 5,
            sectionScore = 4,
            outroScore = 3,
            rhythmScore = 4,
            overallScore = 4,
            comment = "还不错",
        )
        vm.initWithTask("dst_1")
        testScheduler.advanceUntilIdle()

        assertTrue(vm.isAlreadyRated)
    }

    @Test
    fun `读回后把上次的分数预填进表单`() = run {
        api.myRating = MyEvaluationResponse(
            id = "eval_1",
            taskId = "dst_1",
            hookScore = 5,
            sectionScore = 4,
            outroScore = 3,
            rhythmScore = 4,
            overallScore = 4,
            comment = "还不错",
        )
        vm.initWithTask("dst_1")
        testScheduler.advanceUntilIdle()

        val s = vm.uiState.value
        assertEquals(5, s.hookScore)
        assertEquals(4, s.sectionScore)
        assertEquals(3, s.outroScore)
        assertEquals(4, s.rhythmScore)
        assertEquals(4, s.overallScore)
        assertEquals("还不错", s.comment)
    }

    @Test
    fun `读回失败不阻断评分——仍可提交`() = run {
        api.failMyRating = true
        vm.initWithTask("dst_1")
        testScheduler.advanceUntilIdle()

        // 拉取失败后 isAlreadyRated 必须是 false（当作"未知"），而不是 true 把入口堵死
        assertFalse(vm.isAlreadyRated)

        vm.setOverallScore(4)
        vm.submit()
        testScheduler.advanceUntilIdle()

        assertEquals(1, api.submitCount)
        assertTrue(vm.submitState.value is EvaluationViewModel.SubmitState.Success)
    }

    @Test
    fun `提交成功后本地标记为已评过`() = run {
        vm.initWithTask("dst_1")
        testScheduler.advanceUntilIdle()
        assertFalse(vm.isAlreadyRated)

        vm.setOverallScore(5)
        vm.submit()
        testScheduler.advanceUntilIdle()

        // 不重新拉接口：提交响应里已经有 id / overall_score
        assertTrue(vm.isAlreadyRated)
        assertEquals(5, vm.myRating.value?.overallScore)
    }

    @Test
    fun `reset 不清掉已评分状态`() = run {
        vm.initWithTask("dst_1")
        testScheduler.advanceUntilIdle()
        vm.setOverallScore(5)
        vm.submit()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.isAlreadyRated)

        vm.reset()

        // reset 只清表单；"这篇已评过"是服务端事实，清掉会让重开弹窗闪成"未评分"
        assertTrue(vm.isAlreadyRated)
        assertEquals(0, vm.uiState.value.overallScore)
    }
}
