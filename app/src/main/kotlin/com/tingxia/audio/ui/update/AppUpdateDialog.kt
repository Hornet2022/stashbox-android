package com.tingxia.audio.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tingxia.audio.data.model.UpdateCheck
import com.tingxia.audio.data.model.hasDownloadUrl
import com.tingxia.audio.data.model.versionLabel

/**
 * "发现新版本"弹窗：显示更新说明 + 「立即更新」。
 *
 * 为什么强制更新不拦着用户点"稍后"：
 * 客户端没有资格替服务端做"你必须升级"的决定 —— 强制升级是**服务端**
 * 用 `min_supported_version_code` 表达的策略，客户端只负责**如实转达**。
 * 但界面必须把后果说清楚（当前版本已不再支持），不能默默放行。
 */
@Composable
fun AppUpdateDialog(
    check: UpdateCheck,
    isDownloading: Boolean,
    downloadProgress: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val info = check.info
    val versionLabel = info?.versionLabel().orEmpty()

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = {
            Text(
                if (check.isUnsupported) {
                    "当前版本已不再支持"
                } else {
                    "发现新版本 $versionLabel"
                }
            )
        },
        text = {
            Column {
                if (check.isUnsupported) {
                    Text(
                        "你当前的版本低于服务端允许的最低版本，部分功能可能无法使用，" +
                            "请尽快升级到 $versionLabel。",
                    )
                }
                val notes = info?.releaseNotes?.takeIf { it.isNotBlank() }
                if (notes != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(notes)
                    }
                } else {
                    Text("服务端未提供更新说明。")
                }
                if (info != null && !info.hasDownloadUrl()) {
                    Text("暂未提供下载地址")
                }
                if (isDownloading) {
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("正在下载 $downloadProgress%")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                // 下载中禁用：连点会重复下载同一个 APK。
                enabled = !isDownloading && info?.hasDownloadUrl() == true,
            ) {
                Text("立即更新")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDownloading) {
                Text("稍后再说")
            }
        },
    )
}
