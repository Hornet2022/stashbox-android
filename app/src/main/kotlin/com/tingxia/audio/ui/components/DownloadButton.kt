package com.tingxia.audio.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.audio.DownloadState
import com.tingxia.audio.audio.OfflineDownloadManager
import com.tingxia.audio.data.sync.PrefetchScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 离线下载按钮（2026-10-02 从装饰品改成真能用）。
 *
 * 之前：`onClick = { /* no-op */ }` + `isCached()` 恒 false —— 图标永远显示
 * "未下载"，点下去毫无反应。现在点一下就真下载，下载中显示进度，
 * 已下载时点一下是删除。
 */
@Composable
fun DownloadButton(
    articleId: String,
    audioUrl: String?,
    title: String? = null,
    durationSec: Int? = null,
    modifier: Modifier = Modifier,
    viewModel: OfflineCacheViewModel = hiltViewModel(),
) {
    if (audioUrl.isNullOrBlank()) return

    val states by viewModel.states.collectAsState()
    val state = remember(articleId, states) { states[articleId] }
    val isCached = remember(articleId, audioUrl, states) { viewModel.isCached(audioUrl) }
    val scope = rememberCoroutineScope()

    IconButton(
        onClick = {
            scope.launch {
                if (isCached) {
                    viewModel.remove(articleId, audioUrl)
                } else {
                    viewModel.download(articleId, audioUrl, title, durationSec)
                }
            }
        },
        modifier = modifier,
    ) {
        when {
            state is DownloadState.Downloading -> CircularProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
            )

            state is DownloadState.DownloadError -> Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = "下载失败：${state.message}",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.error,
            )

            isCached || state is DownloadState.Downloaded -> Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "已下载到本地，点按删除",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )

            else -> Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = "下载到本地，离线可听",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@HiltViewModel
class OfflineCacheViewModel @Inject constructor(
    private val offlineDownloadManager: OfflineDownloadManager,
    private val scheduler: PrefetchScheduler,
) : ViewModel() {
    val states = offlineDownloadManager.states

    fun isCached(audioUrl: String): Boolean = offlineDownloadManager.isCached(audioUrl)

    /** 立即下载（用户主动点的，要看到反馈，不能丢给 WorkManager 静默跑）。 */
    fun download(articleId: String, audioUrl: String, title: String?, durationSec: Int?) {
        viewModelScope.launch {
            offlineDownloadManager.download(articleId, audioUrl, title, durationSec)
        }
    }

    /** 交给 WorkManager 后台跑（列表页批量预加载用，不需要进度反馈）。 */
    fun prefetchInBackground(articleId: String, audioUrl: String, title: String?, durationSec: Int?) {
        scheduler.prefetchAudio(articleId, audioUrl, title, durationSec)
    }

    fun remove(articleId: String, audioUrl: String) {
        viewModelScope.launch { offlineDownloadManager.remove(articleId, audioUrl) }
    }
}
