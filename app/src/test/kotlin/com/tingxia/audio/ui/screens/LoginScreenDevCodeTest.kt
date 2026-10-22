package com.tingxia.audio.ui.screens

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tingxia.audio.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 登录页 dev code 必须**每设备一个**（2026-10-22 改口径）。
 *
 * ## 背景：两个相反的坑
 *
 * **坑一（2026-10-21 修的）**：默认 code 写死成
 * `dev_wechat_${BuildConfig.DEBUG_USER_ID}`，后端按 `open_id = "wx_" + code`
 * 派生账号 —— 于是**每个没手改输入框的测试员都登进同一个账号**。
 * 文章、配额、收藏、反馈、评分全部串给下一个人，配额还是共享的。
 *
 * **坑二（2026-10-22 修的）**：改成「每安装一个随机串」之后，
 * **清数据 / 重装就换一个 code → 换一个新 uid**。于是后台用户列表
 * 会因为「重装一次」而膨胀；开发/测试反复 pm clear 更是天天多几行
 * （实测一天 pm clear 4 次就多 4 个用户，用户列表被测试行为淹没）。
 *
 * ## 现在要求的三条性质
 *
 * - **同一台设备恒定**：重装 / 清数据后还是同一个 uid（靠 ANDROID_ID 派生）
 * - **不同设备不同**：两台手机上的测试员不能撞在同一个账号上
 * - **取不到设备指纹时退回随机**：宁可多一个账号，也不能所有人共用一个
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
    fun `两台不同设备拿到不同的 code`() {
        // 两个互相独立的 store = 两台设备，各自的设备指纹不同
        val deviceA = DevLoginCodeProvider(
            InMemoryStore(),
            deviceSuffix = { "aaaaaaaaaaaa" },
            randomSuffix = { "zzzzzzzzzzzz" },
        )
        val deviceB = DevLoginCodeProvider(
            InMemoryStore(),
            deviceSuffix = { "bbbbbbbbbbbb" },
            randomSuffix = { "zzzzzzzzzzzz" },
        )

        assertNotEquals(
            "不同设备必须拿到不同 code —— 相同 code 就是同一个账号，数据会串",
            deviceA.code(),
            deviceB.code(),
        )
    }

    @Test
    fun `同一台设备重装后还是同一个 code`() {
        // 重装 = 存档没了（新的空 store），但设备指纹没变
        val device = DevLoginCodeProvider(
            InMemoryStore(),
            deviceSuffix = { "aaaa1111bbbb" },
            randomSuffix = { "zzzzzzzzzzzz" },
        )
        val first = device.code()

        // 模拟"卸载重装"：全新的 store（没有存档）+ 同一个设备指纹
        val afterReinstall = DevLoginCodeProvider(
            InMemoryStore(),
            deviceSuffix = { "aaaa1111bbbb" },
            randomSuffix = { "yyyyyyyyyyyy" },
        ).code()

        assertEquals(
            "同一台设备重装后必须还是同一个 code（否则后台用户列表会膨胀）",
            first,
            afterReinstall,
        )
    }

    @Test
    fun `取不到设备指纹时退回随机`() {
        // ANDROID_ID 拿不到（或命中已知坏值）→ 退回随机，
        // 绝不能让所有这类设备共用一个账号（那是 2026-10-21 那个 bug 的翻版）。
        val provider = DevLoginCodeProvider(
            InMemoryStore(),
            deviceSuffix = { null },
            randomSuffix = { "randomfallback" },
        )
        assertEquals("dev_wechat_randomfallback", provider.code())
    }

    @Test
    fun `默认取不到设备指纹时两次也不同`() {
        // 用真实的随机源（UUID）再验一次：不能因为"恰好注入了不同随机源"才通过。
        // 默认 deviceSuffix = null → 走随机兜底。
        val codeA = DevLoginCodeProvider(InMemoryStore()).code()
        val codeB = DevLoginCodeProvider(InMemoryStore()).code()

        assertNotEquals("取不到设备指纹时不同安装也必须不同 code", codeA, codeB)
    }

    @Test
    fun `android_id 取到时后缀稳定且不是原文`() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
            "1234567890abcdef",
        )
        val first = androidIdSuffix(context)
        val second = androidIdSuffix(context)

        assertNotNull("应当取到设备指纹", first)
        assertEquals("同一台设备的后缀必须稳定", first, second)
        assertEquals(DEV_LOGIN_CODE_RANDOM_LENGTH, first!!.length)
        assertNotEquals(
            "不能把 ANDROID_ID 原文送出去（那是设备标识/个人信息）",
            "1234567890abcdef",
            first,
        )
    }

    @Test
    fun `已知坏值的 android_id 视为取不到设备指纹`() {
        // 部分定制 ROM 对所有设备返回同一个常量 —— 拿它当指纹会让
        // 所有这类设备共用一个账号（数据串台 + 配额共享）。
        val context = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
            KNOWN_BAD_ANDROID_ID,
        )
        assertNull("坏值必须当无效处理", androidIdSuffix(context))
    }

    @Test
    fun `android_id 为空时视为取不到设备指纹`() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "")
        assertNull(androidIdSuffix(context))
    }

    @Test
    fun `已存过 code 时不因设备指纹变化而改_零迁移`() {
        // 老用户（已经存了随机 code）必须继续用自己那个 ——
        // 否则他们下次登录会变成新账号，文章/配额"看起来丢了"。
        val store = InMemoryStore("dev_wechat_legacycode")
        val provider = DevLoginCodeProvider(
            store,
            deviceSuffix = { "newdevice01" },
            randomSuffix = { "shouldnotuse" },
        )
        assertEquals("dev_wechat_legacycode", provider.code())
        assertEquals("不该重新落盘", 0, store.writeCount)
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
