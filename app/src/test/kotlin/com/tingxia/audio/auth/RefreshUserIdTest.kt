package com.tingxia.audio.auth

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * refresh 时 user_id 的哨兵值不能覆盖真实身份（2026-10-06 修）。
 *
 * 原实现是 `val uidLong = resp.user_id.toLongOrNull() ?: 0L`，然后把 0 **无条件**
 * 写进 DataStore 的 USER_ID_KEY。后端 `user_id` 是 String（可能是 "" 或 null 形态），
 * 解析失败时 0 就这么落盘了 —— 于是这个用户的配额 / 收藏 / 归属全被记到共享的
 * user-0 桶上，而 AuthViewModel 只判 `userId != null`，0 看起来是个完全正常的会话，
 * 损坏在界面上不可见。
 *
 * 这里锁的规则：refresh 成功后落盘的身份，要么来自响应里的**有效** user_id，
 * 要么是**原样保留**已存的真身份；任何情况下都不能写出 0。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RefreshUserIdTest {

    /** 记录每次落盘的 userId，用于断言"0 从未被写进去"。 */
    private class FakeTokenManager(context: Context, storedUserId: Long?) : TokenManager(context) {
        var savedUserIds = mutableListOf<Long>()
        var stored: Long? = storedUserId

        override suspend fun getAccessToken(): String? = "old-access"
        override suspend fun getRefreshToken(): String? = "old-refresh"
        override suspend fun getUserId(): Long? = stored

        override suspend fun saveTokens(access: String, refresh: String, userId: Long) {
            savedUserIds += userId
            stored = userId
        }
    }

    /** 只实现 refresh（其它端点在本测试里不该被调用）。 */
    private class FakeAuthApi(
        private val userIdInResponse: String,
        private val fail: Boolean = false,
    ) : AuthApi {
        override suspend fun refresh(req: RefreshRequest): AuthResponse {
            if (fail) throw IOException("refresh endpoint down")
            return AuthResponse(
                access_token = "new-access",
                refresh_token = "new-refresh",
                user_id = userIdInResponse,
                expires_in = 3600,
            )
        }

        override suspend fun wechatLogin(req: WechatLoginRequest): AuthResponse = error("not used")
        override suspend fun logout(): LogoutResponse = error("not used")
        override suspend fun me(): UserInfoResponse = error("not used")
        override suspend fun issueToken(req: TokenIssueRequest): TokenIssueResponse = error("not used")
    }

    private fun client(
        tokenManager: FakeTokenManager,
        userIdInResponse: String,
        fail: Boolean = false,
    ): RefreshClient = RefreshClient(
        authApiProvider = javax.inject.Provider { FakeAuthApi(userIdInResponse, fail) },
        tokenManager = tokenManager,
    )

    private fun newTokenManager(storedUserId: Long?) =
        FakeTokenManager(RuntimeEnvironment.getApplication(), storedUserId)

    @Test
    fun `user_id 非数字时保留已存的真身份`() = runTest {
        val tm = newTokenManager(storedUserId = 6892L)

        val token = client(tm, userIdInResponse = "not-a-number").refreshIfPossible()

        assertEquals("token 本身仍要续上", "new-access", token)
        assertEquals(listOf(6892L), tm.savedUserIds)
        assertEquals(6892L, tm.stored)
    }

    @Test
    fun `user_id 为空串时保留已存的真身份`() = runTest {
        val tm = newTokenManager(storedUserId = 6892L)

        client(tm, userIdInResponse = "").refreshIfPossible()

        assertEquals(listOf(6892L), tm.savedUserIds)
    }

    @Test
    fun `user_id 为字面量 0 时不能当成真实身份`() = runTest {
        // 后端 users.id 是自增主键，从 1 开始 → 0 只可能是哨兵，不是某个用户。
        val tm = newTokenManager(storedUserId = 6892L)

        client(tm, userIdInResponse = "0").refreshIfPossible()

        assertEquals(listOf(6892L), tm.savedUserIds)
        assertTrue("0 绝不能落盘", tm.savedUserIds.none { it == 0L })
    }

    @Test
    fun `user_id 有效时正常更新身份`() = runTest {
        val tm = newTokenManager(storedUserId = 6892L)

        val token = client(tm, userIdInResponse = "7").refreshIfPossible()

        assertEquals("new-access", token)
        assertEquals(listOf(7L), tm.savedUserIds)
    }

    @Test
    fun `响应与本地都没有可用身份时放弃本次 refresh 而不是写 0`() = runTest {
        val tm = newTokenManager(storedUserId = 0L) // 已被旧版本写脏

        val token = client(tm, userIdInResponse = "not-a-number").refreshIfPossible()

        // 返回 null = 交给 AuthInterceptor 走"会话失效"分支：至少能把用户送回登录页，
        // 而不是静默地错记到 user 0 桶里。
        assertNull(token)
        assertTrue("任何情况下都不能把 0 写进存储", tm.savedUserIds.isEmpty())
    }

    @Test
    fun `refresh 本身失败时不碰已存身份`() = runTest {
        val tm = newTokenManager(storedUserId = 6892L)

        val token = client(tm, userIdInResponse = "7", fail = true).refreshIfPossible()

        assertNull(token)
        assertTrue("refresh 失败时不能顺手改写身份", tm.savedUserIds.isEmpty())
    }
}