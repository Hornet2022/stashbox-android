package com.tingxia.audio.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tingxia.audio.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 登录页 dev code 必须**每安装一个**（2026-10-21）。
 *
 * ## 背景
 *
 * 原来默认 code 写死成 `dev_wechat_${BuildConfig.DEBUG_USER_ID}`，后端按
 * `open_id = "wx_" + code` 派生账号 —— 于是**每个没手改这个输入框的测试员
 * 都登进同一个账号**。真机上那个共享账号（id 2648）已经攒了 5 篇文章，
 * 而且配额是共享的：第一个登录的人把大家这个月的额度用掉一半。
 * 文章、配额、收藏、反馈、评分全部串给下一个人 —— 这是数据串台。
 *
 * ## 现在要求的两条性质
 *
 * - **同一次安装内稳定**：杀进程重开 App 还得是同一个账号（否则用户昨天下的
 *   音频、记的进度全对不上）
 * - **不同安装之间不同**：否则两台手机上的测试员还是撞在同一个账号上
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LoginScreenDevCodeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** 内存版存储：模拟"一台设备上的一次安装"。 */
    private class InMemoryStore(initial: String? = null) : DevLoginCodeStore {
        var stored: String? = initial
            private set
        var writeCount: Int = 0
            private set

        override fun read(): String? = stored
        override fun write(code: String) {
            stored = code
            writeCount++
        }
    }

    @Test
    fun `两次安装拿到不同的 code`() {
        // 两个互相独立的 store = 两台设备（各自的"安装"）
        val installA = DevLoginCodeProvider(InMemoryStore(), randomSuffix = { "aaaaaaaaaaaa" })
        val installB = DevLoginCodeProvider(InMemoryStore(), randomSuffix = { "bbbbbbbbbbbb" })

        assertNotEquals(
            "不同安装必须拿到不同 code —— 相同 code 就是同一个账号，数据会串",
            installA.code(),
            installB.code(),
        )
    }

    @Test
    fun `默认随机源下两次安装也不同`() {
        // 用真实的随机源（UUID）再验一次：不能因为"恰好注入了不同随机源"才通过
        val codeA = DevLoginCodeProvider(InMemoryStore()).code()
        val codeB = DevLoginCodeProvider(InMemoryStore()).code()

        assertNotEquals("不同安装必须拿到不同 code", codeA, codeB)
    }

    @Test
    fun `同一次安装 code 稳定_重开进程还是同一个账号`() {
        val store = InMemoryStore()
        var randomCalls = 0
        val provider = DevLoginCodeProvider(store) {
            randomCalls++
            "0123456789ab"
        }

        val first = provider.code()
        val second = provider.code()
        // 模拟"杀进程重开"：换一个 provider、换一个 store 实例，读的还是同一份落盘数据
        val afterRestart = DevLoginCodeProvider(store) {
            randomCalls++
            "ffffffffffff"
        }.code()

        assertEquals("同一次安装必须始终同一个 code", first, second)
        assertEquals("重开进程后必须还是同一个 code", first, afterRestart)
        assertEquals("随机源只该跑一次（第二次就该读存档了）", 1, randomCalls)
        assertEquals("存档只该写一次", 1, store.writeCount)
    }

    @Test
    fun `首次启动没有存档时能生成并落盘`() {
        val store = InMemoryStore(null)

        val code = DevLoginCodeProvider(store) { "0123456789ab" }.code()

        assertTrue("code 必须带占位前缀", code.startsWith(DEV_LOGIN_CODE_PREFIX))
        assertEquals("生成的 code 必须落盘", code, store.stored)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun `空串存档视作没有存档`() {
        // 空 code 会被登录按钮的 ifBlank { "anonymous" } 兜成 "anonymous"，
        // 那还是一个共享账号 —— 所以空串必须当"没存过"处理。
        val store = InMemoryStore("")

        val code = DevLoginCodeProvider(store) { "0123456789ab" }.code()

        assertEquals("dev_wechat_0123456789ab", code)
    }

    @Test
    fun `code 长得像占位串而不是固定常量`() {
        val code = DevLoginCodeProvider(InMemoryStore()) { "0123456789ab" }.code()

        assertEquals("dev_wechat_0123456789ab", code)
        assertNotEquals(
            "默认 code 不能还是那个写死的常量（那是所有人共用一个账号的根因）",
            "dev_wechat_${BuildConfig.DEBUG_USER_ID}",
            code,
        )
    }

    @Test
    fun `真实 SharedPreferences 落盘后重开还是同一个 code`() {
        val context = RuntimeEnvironment.getApplication()

        val first = DevLoginCodeProvider(SharedPreferencesDevLoginCodeStore(context)).code()
        // 模拟进程重启：全新的 provider + 全新的 store 实例
        val afterRestart =
            DevLoginCodeProvider(SharedPreferencesDevLoginCodeStore(context)).code()

        assertTrue("必须带占位前缀", first.startsWith(DEV_LOGIN_CODE_PREFIX))
        assertEquals("杀进程重开后必须还是同一个 code", first, afterRestart)
    }

    @Test
    fun `登录页默认填的是本机随机占位 code`() {
        composeTestRule.setContent {
            MaterialTheme {
                LoginScreen()
            }
        }

        // 输入框里的默认值必须长得像占位串
        composeTestRule.onNodeWithText(DEV_LOGIN_CODE_PREFIX, substring = true)
            .assertIsDisplayed()
        // 并且说清楚它只是 OAuth 的临时替身
        composeTestRule.onNodeWithText("临时占位：本机随机生成，接入真 OAuth 后由微信下发")
            .assertIsDisplayed()
    }
}
