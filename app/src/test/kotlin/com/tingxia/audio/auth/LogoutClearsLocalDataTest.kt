package com.tingxia.audio.auth

import android.content.Context
import androidx.room.Room
import com.tingxia.audio.audio.OfflineDownloadManager
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.audio.PlaybackState
import androidx.media3.exoplayer.ExoPlayer
import com.tingxia.audio.data.local.AppDatabase
import com.tingxia.audio.data.local.DownloadedAudioEntity
import com.tingxia.audio.data.local.LocalUserDataCleaner
import com.tingxia.audio.data.local.PlaybackProgressEntity
import com.tingxia.audio.data.sync.PrefetchScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * 登出必须把**上一个用户留在本机的东西全清掉**（2026-10-21）。
 *
 * ## 之前漏了什么
 *
 * `AuthRepository.logout()` 原来只调 `tokenManager.clear()`（清 DataStore 里的 JWT），
 * 本机还留着上一个用户的：
 * - Room `tingxia.db` 的 **downloaded_audio**（下载台账）与 **playback_progress**（播放进度）；
 * - Media3 `SimpleCache`（`<cacheDir>/audio_cache`，LRU 100MB，下载与播放共用）里的音频字节。
 *
 * 共享手机（内测时 testers 互相传手机）下，下一个登录的人会直接看到上一个用户的
 * 下载列表、续播位置和本地缓存 —— 这是**跨账号的数据泄漏**。
 *
 * ## 这里锁什么
 *
 * - 登出会清空两张 Room 表；
 * - 登出可重复调用、没缓存过东西时也不抛（幂等）；
 * - 服务端注销失败（离线）时，token 与本机数据照样清；
 * - 登出会清掉 Media3 缓存里的音频文件。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogoutClearsLocalDataTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var offline: OfflineDownloadManager
    private lateinit var cleaner: LocalUserDataCleaner
    private lateinit var player: PlayerController
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var tokenManager: FakeTokenManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = RuntimeEnvironment.getApplication()
        // 内存库：测试不碰真机上的 tingxia.db
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        offline = OfflineDownloadManager(context, db.downloadedAudioDao())
        exoPlayer = ExoPlayer.Builder(context).build()
        player = PlayerController(exoPlayer)
        cleaner = LocalUserDataCleaner(db, offline, PrefetchScheduler(context), player)
        tokenManager = FakeTokenManager(context)
    }

    @After
    fun tearDown() {
        exoPlayer.release()
        db.close()
        Dispatchers.resetMain()
    }

    /** 假 TokenManager：全内存，不碰真实 DataStore。 */
    private class FakeTokenManager(context: Context) : TokenManager(context) {
        var cleared = false
            private set

        override suspend fun getAccessToken(): String? = null
        override suspend fun getRefreshToken(): String? = null
        override suspend fun getUserId(): Long? = null
        override suspend fun saveTokens(access: String, refresh: String, userId: Long) = Unit

        override suspend fun clear() {
            // 真实实现是 DataStore 写盘（挂起点），这里也留一个挂起点：
            // 登出清理跑在 NonCancellable 里，挂起点必须能过。
            yield()
            cleared = true
        }
    }

    /** 假 AuthApi：只有 logout 会被调用。 */
    private class FakeAuthApi(
        private val onLogout: suspend () -> Unit = {},
    ) : AuthApi {
        override suspend fun wechatLogin(req: WechatLoginRequest): AuthResponse = error("not used")
        override suspend fun refresh(req: RefreshRequest): AuthResponse = error("not used")
        override suspend fun logout(): LogoutResponse {
            onLogout()
            return LogoutResponse()
        }

        override suspend fun me(): UserInfoResponse = error("not used")
        override suspend fun issueToken(req: TokenIssueRequest): TokenIssueResponse = error("not used")
    }

    private fun repo(onLogout: suspend () -> Unit = {}) =
        AuthRepository(FakeAuthApi(onLogout), tokenManager, cleaner)

    private suspend fun seedLocalData() {
        db.downloadedAudioDao().upsert(
            DownloadedAudioEntity(
                articleId = "a1",
                audioUrl = "https://example.com/a1.mp3",
                title = "上一篇用户的文章",
                pinned = true,
                cachedBytes = 1024,
                expectedBytes = 1024,
            )
        )
        db.playbackProgressDao().upsert(
            PlaybackProgressEntity(articleId = "a1", positionMs = 42_000L, durationMs = 60_000L)
        )
    }

    @Test
    fun `登出会清空 Room 里的下载台账与播放进度`() = runTest(dispatcher) {
        seedLocalData()
        assertEquals(1, db.downloadedAudioDao().all().size)

        repo().logout()

        assertTrue(
            "上一个用户的下载台账必须被清空，否则下一个人能直接看到它",
            db.downloadedAudioDao().all().isEmpty(),
        )
        assertNull(
            "上一个用户的播放进度必须被清空，否则下一个人会从他没听过的地方接着播",
            db.playbackProgressDao().byArticle("a1"),
        )
    }

    @Test
    fun `登出可以重复调用_第二次不抛异常`() = runTest(dispatcher) {
        seedLocalData()

        repo().logout()
        repo().logout() // 幂等：再点一次「退出登录」不能崩

        assertTrue(db.downloadedAudioDao().all().isEmpty())
        assertNull(db.playbackProgressDao().byArticle("a1"))
    }

    @Test
    fun `什么都没缓存过时登出也不抛异常`() = runTest(dispatcher) {
        // 空库、空缓存 —— 从没登录过 / 已经清干净了
        repo().logout()

        assertTrue(db.downloadedAudioDao().all().isEmpty())
    }

    @Test
    fun `服务端注销失败时本地 token 与本机数据仍要清掉`() = runTest(dispatcher) {
        seedLocalData()
        val offlineRepo = repo { throw IOException("connection reset") }

        offlineRepo.logout() // 离线登出：不该抛，也不该留下任何东西

        assertTrue("服务端挂了也不能让用户登不出去", tokenManager.cleared)
        assertTrue(
            "离线登出同样要清本机数据",
            db.downloadedAudioDao().all().isEmpty(),
        )
        assertNull(db.playbackProgressDao().byArticle("a1"))
    }

    @Test
    fun `登出会先停掉播放器_否则进度回调会把上一个用户的进度写回来`() = runTest(dispatcher) {
        // 2026-10-21 真机 E2E 实测到的残留：清理完表之后，
        // 播放器的 10s 进度上报又把上一条用户的播放位置写回了本地库
        // （实测残留行 position=93156，updated_at 晚于清理时刻）。
        // 共享设备上，下一个人登录就能看到别人的续播位置。
        //
        // 所以断言的是**播放器被停了** —— 那是"进度不会被写回来"的充分条件。
        player.play("http://example.com/a.wav", "标题", null, null, "art_x")
        assertEquals(PlaybackState.PLAYING, player.state.value)
        seedLocalData()

        repo().logout()

        assertEquals(
            "登出必须停播：否则 10s 一次的进度上报会把上一个用户的播放位置写回本地库",
            PlaybackState.STOPPED,
            player.state.value,
        )
        assertNull("本机的播放进度必须被清掉", db.playbackProgressDao().byArticle("a1"))
    }

    @Test
    fun `登出会清掉 Media3 缓存里的音频文件`() = runTest(dispatcher) {
        // 直接往 SimpleCache 里塞一个资源（走 Media3 自己的 API）。
        // 不走 download()：那条路要连真服务器，而且它自己有个与本次改动无关的
        // 老问题（media3 1.4.1 的 startFile 要求先 startReadWrite 建好 content，
        // 见 OfflineDownloadManager.download 的报告）。
        val cache = offline.cache()
        val key = "https://example.com/a1.mp3"
        cache.startReadWrite(key, 0, 1024)
        val file = cache.startFile(key, 0, 1024)
        file.outputStream().use { it.write(ByteArray(1024)) }
        cache.commitFile(file, 1024)
        assertTrue("前置条件：缓存里必须先有东西", cache.getKeys().contains(key))

        repo().logout()

        assertTrue(
            "登出后缓存里不能还留着上一个用户的音频",
            offline.cache().getKeys().isEmpty(),
        )
    }
}
