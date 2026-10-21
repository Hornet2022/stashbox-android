package com.tingxia.audio.data.repository

import com.tingxia.audio.data.model.AppVersionResponse
import com.tingxia.audio.data.remote.ApiException
import com.tingxia.audio.data.remote.AppUpdateApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * 版本接口的仓库层回归。
 *
 * 锁两件事：
 * 1. 成功响应 → 领域模型（字段名别搞错：`latest_version_code` 而不是 `versionCode`）；
 * 2. 网关的 4xx → [com.tingxia.audio.data.remote.ApiException]，
 *    而不是裸 HttpException —— 否则友好文案按 HTTP 码分流，
 *    分不出"接口不存在"和"服务不可用"。
 */
class AppUpdateRepositoryTest {

    private class FakeAppUpdateApi(
        private val response: AppVersionResponse? = null,
        private val error: HttpException? = null,
    ) : AppUpdateApi {
        var callCount = 0
            private set

        override suspend fun getLatestVersion(): AppVersionResponse {
            callCount++
            error?.let { throw it }
            return response ?: AppVersionResponse()
        }
    }

    private fun httpError(code: Int, body: String): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaType()))
    )

    @Test
    fun `成功响应被翻译成领域模型`() = runTest {
        val api = FakeAppUpdateApi(
            AppVersionResponse(
                latestVersionCode = 7,
                latestVersionName = "0.2.0",
                minSupportedVersionCode = 3,
                downloadUrl = "https://example.com/app.apk",
                releaseNotes = "修复若干问题",
                publishedAt = "2026-10-20T10:00:00Z",
                mandatory = false,
            )
        )
        val repo = AppUpdateRepository(api)

        val info = repo.latestVersion()

        assertEquals(7, info.latestVersionCode)
        assertEquals("0.2.0", info.latestVersionName)
        assertEquals(3, info.minSupportedVersionCode)
        assertEquals("https://example.com/app.apk", info.downloadUrl)
        assertEquals("修复若干问题", info.releaseNotes)
        assertEquals("2026-10-20T10:00:00Z", info.publishedAt)
        assertEquals(false, info.mandatory)
    }

    @Test
    fun `网关的错误信封被解析成 ApiException`() = runTest {
        val api = FakeAppUpdateApi(
            error = httpError(404, """{"code":40400,"message":"接口不存在","data":null}""")
        )
        val repo = AppUpdateRepository(api)

        val thrown = runCatching { repo.latestVersion() }.exceptionOrNull()

        assertTrue(thrown is ApiException)
        val apiEx = thrown as ApiException
        // 客户端要靠 httpCode 区分"服务端没这个接口"和"音频没蒸馏好"
        // （两者在网关里都是 404 + 40400）。
        assertEquals(404, apiEx.httpCode)
        assertEquals(40400, apiEx.bizCode)
    }

    @Test
    fun `每次检查都会真的发一次请求`() = runTest {
        val api = FakeAppUpdateApi(AppVersionResponse(latestVersionCode = 1))
        val repo = AppUpdateRepository(api)

        repo.latestVersion()
        repo.latestVersion()

        assertEquals(2, api.callCount)
    }
}
