package com.tingxia.audio.ui.offline

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 通勤预加载必须**真的下载到本机**（2026-10-03 修）。
 *
 * 修之前：`OfflineDownloadViewModel.batchWarm` 全程只调
 * `variantRepository.warmVariant()`（服务端 ffmpeg 转码），
 * `OfflineDownloadManager` 连构造参数都没有 —— 设备上零字节。
 * 而页面文案写着「预热**到本机**」「通勤无网时也能秒开」，
 * 弹窗还报「预热完成 成功 N 篇」。用户据此进地铁必然打不开。
 *
 * 原来的 KDoc 辩解是「本地缓存由 ExoPlayer CacheDataSource 透明处理」，
 * 但那只在**播放之后**才生效，而预加载存在的意义就是播放**之前**下好。
 *
 * 这里用源码断言锁契约：这类「少调一个方法」的回归人眼很难在 review 时发现，
 * 但后果是用户拿到一个假的离线承诺。
 */
class OfflineDownloadViewModelContractTest {

    private val source: String by lazy {
        val candidates = listOf(
            "app/src/main/kotlin/com/tingxia/audio/ui/offline/OfflineDownloadViewModel.kt",
            "src/main/kotlin/com/tingxia/audio/ui/offline/OfflineDownloadViewModel.kt",
        )
        val f = candidates.firstNotNullOfOrNull { path -> File(path).takeIf { it.exists() } }
            ?: error("找不到 OfflineDownloadViewModel.kt（cwd=${File(".").absolutePath}）")
        f.readText()
    }

    @Test
    fun batchWarm_必须调用本地下载_不能只转码到服务端() {
        val batchWarm = source.substringAfter("fun batchWarm()")
            .substringBefore("fun dismissSummary()")

        assertTrue(
            "batchWarm 必须调用 offlineDownloadManager.download —— " +
                "只调 warmVariant 只是让服务端有货，设备上仍然是零字节",
            batchWarm.contains("offlineDownloadManager.download("),
        )
    }

    @Test
    fun 构造函数必须注入下载管理器() {
        assertTrue(
            "OfflineDownloadManager 必须注入，否则根本没法下载到本机",
            source.contains("private val offlineDownloadManager: OfflineDownloadManager"),
        )
    }

    @Test
    fun 流程必须先转码再取url最后下载() {
        val batchWarm = source.substringAfter("fun batchWarm()")
            .substringBefore("fun dismissSummary()")

        val warmIdx = batchWarm.indexOf("warmVariant(")
        val pickIdx = batchWarm.indexOf("variantSelection.pick(")
        val downloadIdx = batchWarm.indexOf("offlineDownloadManager.download(")

        assertTrue("应先触发服务端转码", warmIdx >= 0)
        assertTrue("warm 之后要取该档的可下载 URL", pickIdx > warmIdx)
        assertTrue("取到 URL 后才下载到本机", downloadIdx > pickIdx)
    }

    @Test
    fun 汇总必须区分已下载与已转码_不能用转码数冒充下载数() {
        assertTrue(
            "BatchSummary 必须区分 transcoded 与 downloaded —— " +
                "只报转码数会让运营以为音频已经在手机上了",
            source.contains("val transcoded: Int") && source.contains("val downloaded: Int"),
        )
    }

    @Test
    fun 页面文案不能把转码说成到本机() {
        val screenCandidates = listOf(
            "app/src/main/kotlin/com/tingxia/audio/ui/offline/OfflineDownloadScreen.kt",
            "src/main/kotlin/com/tingxia/audio/ui/offline/OfflineDownloadScreen.kt",
        )
        val f = screenCandidates.firstNotNullOfOrNull { path -> File(path).takeIf { it.exists() } }
            ?: error("找不到 OfflineDownloadScreen.kt")
        val screen = f.readText()

        // 汇总弹窗必须报出真实落盘数
        assertTrue(
            "汇总弹窗要显示 summary.downloaded（真正落盘的数量）",
            screen.contains("summary.downloaded"),
        )
        // 旧文案把「服务端转码」说成「会自动命中本地缓存」，是错的因果
        assertTrue(
            "旧文案「转码结果在服务端保留，下次播放会自动命中本地缓存」已修正为如实描述",
            !screen.contains("下次播放会自动命中本地缓存"),
        )
    }
}
