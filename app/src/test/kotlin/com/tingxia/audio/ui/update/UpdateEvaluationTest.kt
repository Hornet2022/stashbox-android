package com.tingxia.audio.ui.update

import com.tingxia.audio.data.model.AppUpdateInfo
import com.tingxia.audio.data.model.UpdateStatus
import com.tingxia.audio.data.model.evaluateUpdate
import com.tingxia.audio.data.model.hasDownloadUrl
import com.tingxia.audio.data.model.versionLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较口径的回归。
 *
 * 这几条规则一旦漂移，用户看到的提示就会自相矛盾
 * （比如"有新版本"和"当前版本已不再支持"同时出现），
 * 所以单独锁住纯逻辑，不依赖任何 Android 组件。
 */
class UpdateEvaluationTest {

    private fun info(
        latestCode: Int = 5,
        latestName: String = "0.2.0",
        minSupported: Int = 0,
        downloadUrl: String = "https://example.com/app.apk",
        mandatory: Boolean = false,
    ) = AppUpdateInfo(
        latestVersionCode = latestCode,
        latestVersionName = latestName,
        minSupportedVersionCode = minSupported,
        downloadUrl = downloadUrl,
        releaseNotes = "更新说明",
        publishedAt = null,
        mandatory = mandatory,
    )

    @Test
    fun `版本相同就是最新`() {
        val check = evaluateUpdate(info(latestCode = 5), installedVersionCode = 5)

        assertEquals(UpdateStatus.UP_TO_DATE, check.status)
        assertFalse(check.isMandatory)
        assertFalse(check.isUnsupported)
    }

    @Test
    fun `服务端版本更大就是有新版本`() {
        val check = evaluateUpdate(info(latestCode = 5), installedVersionCode = 1)

        assertEquals(UpdateStatus.UPDATE_AVAILABLE, check.status)
        assertFalse(check.isMandatory)
    }

    @Test
    fun `本机比服务端还新也算最新`() {
        // 内测包的 versionCode 经常比线上发布版大 —— 那时不该报"有新版本"
        val check = evaluateUpdate(info(latestCode = 3), installedVersionCode = 9)

        assertEquals(UpdateStatus.UP_TO_DATE, check.status)
    }

    @Test
    fun `低于最低支持版本就是不支持且强制更新`() {
        val check = evaluateUpdate(info(latestCode = 5, minSupported = 4), installedVersionCode = 2)

        assertEquals(UpdateStatus.UPDATE_AVAILABLE, check.status)
        assertTrue(check.isUnsupported)
        assertTrue(check.isMandatory)
        assertTrue(check.summaryText().contains("当前版本已不再支持"))
    }

    @Test
    fun `服务端标了 mandatory 也算强制更新`() {
        val check = evaluateUpdate(info(latestCode = 5, mandatory = true), installedVersionCode = 4)

        assertTrue(check.isMandatory)
    }

    @Test
    fun `没有新版本时 mandatory 不该逼用户升级`() {
        // 服务端的 mandatory 是全局标记；本机已经是最新版时不该弹"必须更新"
        val check = evaluateUpdate(info(latestCode = 5, mandatory = true), installedVersionCode = 5)

        assertFalse(check.isMandatory)
    }

    @Test
    fun `已是最新时给出一句结论`() {
        val check = evaluateUpdate(info(latestCode = 5), installedVersionCode = 5)

        assertEquals("已是最新版本", check.summaryText())
    }

    @Test
    fun `有新版本时给出版本号`() {
        val check = evaluateUpdate(info(latestCode = 5, latestName = "0.2.0"), installedVersionCode = 1)

        assertEquals("有新版本 0.2.0", check.summaryText())
    }

    @Test
    fun `版本名没配时退化成一个有信息的标签`() {
        // 后端用环境变量配版本名，没配就是空串 —— 直接显示会变成"有新版本 "
        assertEquals("版本 5", info(latestCode = 5, latestName = "").versionLabel())
        assertEquals("新版本", info(latestCode = 0, latestName = "").versionLabel())
    }

    @Test
    fun `下载地址为空时能被认出来`() {
        assertFalse(info(downloadUrl = "").hasDownloadUrl())
        assertTrue(info(downloadUrl = "https://example.com/app.apk").hasDownloadUrl())
    }
}
