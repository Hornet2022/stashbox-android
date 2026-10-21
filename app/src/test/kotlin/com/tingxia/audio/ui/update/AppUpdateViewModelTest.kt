package com.tingxia.audio.ui.update

import com.tingxia.audio.BuildConfig
import com.tingxia.audio.data.model.AppUpdateInfo
import com.tingxia.audio.data.model.AppVersionResponse
import com.tingxia.audio.data.model.UpdateStatus
import com.tingxia.audio.data.remote.ApiException
import com.tingxia.audio.data.remote.AppUpdateApi
import com.tingxia.audio.data.repository.AppUpdateRepository
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
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * 更新流程的状态机回归。
 *
 * 关注四个用户能直接看到的结论：
 * - 已是最新 / 有新版本 / 检查失败；
 * - 服务端没给下载地址时**说清楚**，而不是闷头失败；
 * - 404（网关"接口不存在"）不能被说成"音频还在蒸馏中"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppUpdateViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var installer: FakeApkInstaller
    private lateinit var viewModel: AppUpdateViewModel

    /** 假的更新仓库：直接给结论，不发网络请求。 */
    private class FakeUpdateRepository(
        private val info: AppUpdateInfo? = null,
        private val error: Throwable? = null,
    ) : AppUpdateRepository(
        object : AppUpdateApi {
            override suspend fun getLatestVersion(): AppVersionResponse =
                throw UnsupportedOperationException("本测试不走真实 api")
        }
    ) {
        override suspend fun latestVersion(): AppUpdateInfo {
            error?.let { throw it }
            return info ?: throw IllegalStateException("测试没有提供版本信息")
        }
    }

    private class FakeApkInstaller : ApkInstaller {
        var called = false
        var result: ApkInstallResult = ApkInstallResult.InstallerLaunched

        override suspend fun downloadAndInstall(
            url: String,
            fileName: String,
            onProgress: (downloaded: Long, total: Long) -> Unit,
        ): ApkInstallResult {
            called = true
            onProgress(50L, 100L)
            return result
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        installer = FakeApkInstaller()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(repository: AppUpdateRepository): AppUpdateViewModel {
        viewModel = AppUpdateViewModel(repository, installer)
        return viewModel
    }

    private fun run(body: suspend TestScope.() -> Unit) = testScope.runTest { body() }

    private fun info(
        latestCode: Int = 2,
        downloadUrl: String = "https://example.com/app.apk",
        minSupported: Int = 0,
        mandatory: Boolean = false,
    ) = AppUpdateInfo(
        latestVersionCode = latestCode,
        latestVersionName = "0.2.0",
        minSupportedVersionCode = minSupported,
        downloadUrl = downloadUrl,
        releaseNotes = "更新说明",
        publishedAt = null,
        mandatory = mandatory,
    )

    @Test
    fun `服务端版本更高时提示有新版本并弹窗`() = run {
        // 版本号要相对 BuildConfig —— 写死数字的话，
        // 每次升 versionCode 这条测试就红一次（2026-10-21 升到 2 时就红过一次）。
        val vm = newViewModel(FakeUpdateRepository(info(latestCode = BuildConfig.VERSION_CODE + 1)))

        vm.check()
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, state.check?.status)
        assertTrue(state.message?.contains("有新版本") == true)
        assertTrue(state.showUpdateDialog)
    }

    @Test
    fun `版本相同时提示已是最新`() = run {
        // 服务端报的就是本机这个版本 → 已是最新
        val vm = newViewModel(FakeUpdateRepository(info(latestCode = BuildConfig.VERSION_CODE)))

        vm.check()
        testScheduler.advanceUntilIdle()

        assertEquals(UpdateStatus.UP_TO_DATE, vm.state.value.check?.status)
        assertEquals("已是最新版本", vm.state.value.message)
        assertFalse(vm.state.value.showUpdateDialog)
    }

    @Test
    fun `检查失败时给出失败文案`() = run {
        val vm = newViewModel(FakeUpdateRepository(error = IOException("网络断了")))

        vm.check()
        testScheduler.advanceUntilIdle()

        val error = vm.state.value.error
        assertTrue(error?.startsWith("检查失败：") == true)
        assertEquals(null, vm.state.value.message)
    }

    @Test
    fun `服务端没有版本接口时不说成音频问题`() = run {
        // 网关的"接口不存在"是 404 + 40400，和"音频没蒸馏好"完全同码；
        // 通用文案会变成"音频还在蒸馏中"，纯误导。
        val vm = newViewModel(
            FakeUpdateRepository(error = ApiException(httpCode = 404, bizCode = 40400, message = "接口不存在"))
        )

        vm.check()
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.error?.contains("服务端未提供版本接口") == true)
    }

    @Test
    fun `没有下载地址时说清楚而不是闷头失败`() = run {
        val vm = newViewModel(FakeUpdateRepository(info(downloadUrl = "")))

        vm.check()
        testScheduler.advanceUntilIdle()
        vm.downloadAndInstall()
        testScheduler.advanceUntilIdle()

        assertEquals("暂未提供下载地址", vm.state.value.error)
        assertFalse(installer.called)
    }

    @Test
    fun `点击立即更新会真的下载并拉起安装器`() = run {
        val vm = newViewModel(FakeUpdateRepository(info()))
        vm.check()
        testScheduler.advanceUntilIdle()

        vm.downloadAndInstall()
        testScheduler.advanceUntilIdle()

        assertTrue(installer.called)
        assertEquals("已打开系统安装器，确认后即可完成更新", vm.state.value.message)
        assertFalse(vm.state.value.isDownloading)
    }
}
