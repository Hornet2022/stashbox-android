package com.tingxia.audio

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CP-DELETE 真机端到端：登录 → 列表 → 详情 → 垃圾桶 → 确认删除 → 回列表。
 *
 * 为什么用 Compose TestRule（而不是 adb input / UiAutomator）
 * ----------------------------------------------------------
 * MIUI 拦截一切非系统签名的**输入注入**：
 * - `adb shell input tap/keyevent` → `SecurityException: INJECT_EVENTS`
 * - 即使是 instrumentation 身份，`UiAutomation.injectInputEvent()` 同样被拒
 *   （实测：UiAutomator 的 `UiObject2.click()` 抛 SecurityException）
 *
 * Compose TestRule 的 `performClick()` 走的是**语义树 action dispatch**
 * （直接调用 Compose 节点的 OnClick），完全不经过 InputManager，
 * 因此在 MIUI 默认设置下也能跑真机端到端。
 *
 * 前置条件（真实环境，非 mock）
 * ----------------------------
 * 1. 设备已连接，app + test APK 已装（`./gradlew installDebug installDebugAndroidTest`）
 * 2. 后端可达（app baseUrl 指向的 api-gateway，dev 为 172.16.5.28:8100）
 * 3. **被测账号下至少有 1 篇 ready 文章**（否则列表为空 → 测试 skip）
 *
 * 运行（绕开 AGP 的安装阶段，避免 MIUI 安装确认打断）：
 *   adb shell am instrument -w -e class com.tingxia.audio.DeleteArticleE2ETest \
 *     com.tingxia.audio.debug.test/androidx.test.runner.AndroidJUnitRunner
 * 或 `./gradlew connectedDebugAndroidTest`（需允许 MIUI 每次安装确认）
 *
 * 回归背景：0030 修复前，删「有评分或有音频变体」的文章会 500（FK 缺级联），
 * UI 提示「服务暂不可用」。本测试锁定该路径。
 */
@RunWith(AndroidJUnit4::class)
class DeleteArticleE2ETest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setupClock() {
        // Compose TestClock:CP9 测试规范里默认 autoAdvance=true。
        // 早先 CP-DRAFT 误判「关掉 autoAdvance 避免 CircularProgressIndicator 卡死」,
        // 实测发现关闭会让 LaunchedEffect 协程永不调度(Composable 仍渲染,
        // 但 `viewModelScope.launch{}` 等不到第一帧推进)→ splash → login 转不过去。
        //
        // 正确做法:保持 autoAdvance=true,所有「等节点出现」都走 `waitUntil { ... }`,
        // 它内部用 50ms 步进轮询,不依赖 waitForIdle()(后者确实会被无限动画卡死)。
        rule.mainClock.autoAdvance = true
    }

    /** 节点是否存在（不抛异常，用于轮询等待）。 */
    private fun ComposeTestRule.exists(text: String): Boolean =
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeTestRule.existsDesc(desc: String): Boolean =
        onAllNodesWithText(desc).fetchSemanticsNodes().isNotEmpty() ||
            runCatching {
                onAllNodes(hasContentDescription(desc)).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)

    @Test
    fun delete_fromDetail_returnsToList_andRemovesItem() {
        // ── 登录（重装/清数据后 token 丢失会停在登录页）──
        if (rule.exists("微信登录（mock）")) {
            rule.onNodeWithText("微信登录（mock）").performClick()
        } else {
            // 登录页可能还在渲染，给一次机会
            runCatching {
                rule.waitUntil(8_000) { rule.exists("微信登录（mock）") }
            }.onSuccess {
                rule.onNodeWithText("微信登录（mock）").performClick()
            }
        }

        // ── 走完 3 步引导 ──
        //    MainActivity.AuthRoot 用本地 SharedPreferences `has_onboarded`
        //    决定首屏；重装/清数据后必为 false → 会先显示 OnboardingScreen。
        //    步骤页只有「上一步 / 下一步 / 完成」，没有跳过。
        var guard = 0
        while (guard++ < 8 && !rule.exists("文章列表")) {
            when {
                rule.exists("完成") -> {
                    rule.onNodeWithText("完成").performClick()
                    Thread.sleep(400)
                }
                rule.exists("下一步") -> {
                    rule.onNodeWithText("下一步").performClick()
                    Thread.sleep(400)
                }
                else -> break
            }
        }

        // ── 等首页（HomeScreen 特征：「文章列表」分区标题）──
        //    登录页/引导页也有「听匣」文案，不能拿它当判据。
        if (!runCatching { rule.waitUntil(45_000) { rule.exists("文章列表") } }.isSuccess) {
            rule.onRoot().printToLog("DeleteE2E")
            org.junit.Assert.fail("引导后首页未出现（找不到「文章列表」），已 dump 语义树到 logcat tag DeleteE2E")
        }

        // ── 等文章列表加载（网络往返）──
        //    列表项标题可能为空（后端无 title）→ 用源标记 "web" 兜底。
        runCatching { rule.waitUntil(25_000) { rule.exists("web") || rule.exists("无标题") } }
            .onFailure {
                org.junit.Assume.assumeTrue(
                    "被测账号下没有可删除的文章（需至少 1 篇 ready），跳过",
                    false,
                )
            }

        // ── 进详情：点列表项（Compose 的 clickable 会 merge 子树语义，
        //    所以按标题/来源文本定位到的就是可点节点）──
        runCatching { rule.onNodeWithText("无标题").performClick() }
            .getOrElse { rule.onNodeWithText("web").performClick() }
        Thread.sleep(400)

        // ── 等详情页（垃圾桶按钮出现）──
        rule.waitUntil(25_000) { rule.existsDesc("删除文章") }

        // ── 点垃圾桶 → 确认弹窗 ──
        rule.onNodeWithContentDescription("删除文章").performClick()
        rule.waitUntil(10_000) { rule.exists("删除这篇内容？") }

        // ── 点「删除」确认 ──
        //    0030 前这里服务端 500 → 弹窗停留并显示「服务暂不可用」
        rule.onNodeWithText("删除").performClick()

        // ── 断言：弹窗关闭 + 回列表 ──
        rule.waitUntil(25_000) { !rule.exists("删除这篇内容？") }
        assertTrue(
            "删除后未回到列表页（未出现「文章列表」）",
            runCatching {
                rule.waitUntil(15_000) { rule.exists("文章列表") }
                true
            }.getOrDefault(false),
        )

        // ── 断言：无错误提示残留 ──
        assertTrue(
            "UI 残留「服务暂不可用」——删除仍失败",
            rule.onAllNodesWithText("服务暂不可用", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )

        // ── 断言：该文章已从列表消失（列表页重新组合时重跑 loadArticles）──
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("无标题").fetchSemanticsNodes().isEmpty() ||
                rule.onAllNodesWithText("web").fetchSemanticsNodes().isEmpty()
        }
        rule.onAllNodesWithText("web").assertCountEquals(0)
    }
}