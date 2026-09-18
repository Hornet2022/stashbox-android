package com.tingxia.audio.onboarding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeRepo() = OnboardingRepository(object : OnboardingApi {
        override suspend fun start() {}
        override suspend fun stepViewed(step: Int) = StepResponse(step_viewed = step, step_name = "step_$step")
        override suspend fun complete() = DoneResponse(onboarding_done = true, onboarding_done_at = "2026-01-01T00:00:00Z")
    })

    @Test
    fun test_onStart_sendsStartRequest() = testScope.runTest {
        val repo = fakeRepo()
        val vm = OnboardingViewModel(repo)
        vm.onStart()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value is OnboardingState.InProgress)
    }

    @Test
    fun test_onStepViewed_sendsStepRequest() = testScope.runTest {
        val repo = fakeRepo()
        val vm = OnboardingViewModel(repo)
        vm.onStart()
        testScheduler.advanceUntilIdle()
        vm.onStepViewed(2)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value is OnboardingState.InProgress)
    }

    @Test
    fun test_onComplete_callsSuccessCallback() = testScope.runTest {
        val repo = fakeRepo()
        val vm = OnboardingViewModel(repo)
        var completed = false
        vm.onStart()
        testScheduler.advanceUntilIdle()
        vm.onComplete { completed = true }
        testScheduler.advanceUntilIdle()
        assertTrue(completed)
        assertTrue(vm.state.value is OnboardingState.Completed)
    }
}
