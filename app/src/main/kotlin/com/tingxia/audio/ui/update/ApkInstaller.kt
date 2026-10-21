package com.tingxia.audio.ui.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.tingxia.audio.ui.friendlyError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * 下载并安装新版本 APK 的结果。
 */
sealed interface ApkInstallResult {
    /** APK 已下载完并交给系统安装器。 */
    data object InstallerLaunched : ApkInstallResult

    /**
     * Android 8+ 用户还没允许"安装未知来源应用"，已把他送到系统设置页。
     * 回来之后需要用户自己再点一次「立即更新」。
     */
    data object UnknownSourceSettingsOpened : ApkInstallResult

    /**
     * 没法直接装（FileProvider 没注册 / 系统没有安装器），
     * 已经退回"用浏览器打开下载地址"—— 这条路永远可用。
     */
    data class BrowserFallback(val reason: String) : ApkInstallResult

    /** 彻底失败：下载失败、拿不到目录、启动不了任何页面。 */
    data class Failed(val reason: String) : ApkInstallResult
}

/**
 * APK 下载 + 安装能力（抽成接口是为了让 ViewModel 能在单测里替换掉）。
 */
interface ApkInstaller {
    /**
     * 下载 [url] 指向的 APK 并拉起安装器。
     *
     * @param onProgress 已下载字节数 / 总字节数（总长度为 -1 表示未知）。
     */
    suspend fun downloadAndInstall(
        url: String,
        fileName: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): ApkInstallResult
}

/**
 * 真实实现：OkHttp 下载 + FileProvider 授权 + 系统安装器。
 *
 * ## 为什么不用 DownloadManager
 *
 * DownloadManager 要靠广播接收 `ACTION_DOWNLOAD_COMPLETE` 才知道下完，
 * 还得处理"App 被杀之后下载还在跑"的状态恢复；应用内更新是一次性的、
 * 用户正在看着的短流程，用 OkHttp 直接流式下载能给出实时进度，
 * 协程被取消时下载也随之取消。
 *
 * ## 为什么必须留"浏览器兜底"
 *
 * 直接安装依赖 manifest 里注册的 FileProvider + `REQUEST_INSTALL_PACKAGES` 权限。
 * 权限没给、系统没装安装器、厂商 ROM 拦了安装 Intent —— 这些都会让
 * "直接安装"这条路走不通。**浏览器打开下载地址永远走得通**（APK 也能装），
 * 所以它是最后一道兜底，而不是可选分支。
 */
class SystemApkInstaller(
    private val context: Context,
    private val client: OkHttpClient,
) : ApkInstaller {

    override suspend fun downloadAndInstall(
        url: String,
        fileName: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): ApkInstallResult = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext ApkInstallResult.Failed("暂未提供下载地址")

        val target = resolveApkFile(fileName)
            ?: return@withContext ApkInstallResult.Failed("无法创建下载目录")

        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ApkInstallResult.Failed("下载失败（HTTP ${response.code}）")
                }
                val body = response.body
                    ?: return@withContext ApkInstallResult.Failed("下载内容为空")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            return@withContext ApkInstallResult.Failed(
                "下载失败：${friendlyError(e, "网络不给力，请检查连接")}"
            )
        }

        install(target, url)
    }

    /**
     * 拉起系统安装器。
     *
     * 顺序很关键：**先查"未知来源"授权，再发安装 Intent**。
     * 反过来的话，用户会看到安装器闪一下就退（系统静默拒绝），
     * 界面上什么提示都没有 —— 用户只会觉得"这 App 有毛病"。
     */
    private fun install(apk: File, downloadUrl: String): ApkInstallResult {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return if (openUnknownSourceSettings()) {
                ApkInstallResult.UnknownSourceSettingsOpened
            } else {
                ApkInstallResult.Failed("请到系统设置里允许本应用安装未知来源应用后再试")
            }
        }

        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        } catch (e: IllegalArgumentException) {
            // manifest 里没有注册 FileProvider（或路径没覆盖这个目录）
            // —— 不是 bug，是"还没合并那段 manifest"，退回浏览器。
            return openInBrowser(downloadUrl, "安装器不可用：${e.message}")
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ApkInstallResult.InstallerLaunched
        } catch (e: ActivityNotFoundException) {
            openInBrowser(downloadUrl, "系统没有可用的安装器")
        }
    }

    /** 跳到"允许安装未知应用"的系统设置页。 */
    private fun openUnknownSourceSettings(): Boolean {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** 兜底：用浏览器打开下载地址（用户自己下载、自己装）。 */
    private fun openInBrowser(url: String, reason: String): ApkInstallResult {
        if (url.isBlank()) return ApkInstallResult.Failed(reason)
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            ApkInstallResult.BrowserFallback(reason)
        } catch (e: ActivityNotFoundException) {
            ApkInstallResult.Failed(reason)
        }
    }

    /**
     * APK 放哪儿。
     *
     * 优先外部 files 目录（`getExternalFilesDir(Download)`）——
     * 它是**应用私有**目录，不需要任何存储权限，卸载即清理；
     * 拿不到（比如没插卡的设备）就退回内部 files 目录。
     * 两个目录都在 `res/xml/file_paths.xml` 里声明过，FileProvider 才能授权。
     */
    private fun resolveApkFile(fileName: String): File? {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { dir ->
            dir.mkdirs()
            return File(dir, safeName)
        }
        val fallback = File(context.filesDir, "updates")
        fallback.mkdirs()
        return File(fallback, safeName)
    }
}
