package com.tingxia.audio.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.tingxia.audio.audio.DownloadState
import com.tingxia.audio.audio.OfflineDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * CP11.0.7 P2.1: 离线缓存状态指示器。
 *
 * 当前设计：Media3 SimpleCache LRU 自动缓存 + ExoPlayer CacheDataSource 透明拦截，
 * 无显式"下载"动作。该按钮仅显示当前缓存命中状态（已缓存 / 未缓存），
 * 首次播放即自动缓存，二次播放命中秒开。
 *
 * - 状态：未缓存（云下载）/ 已缓存（云完成）
 */
@Composable
fun DownloadButton(
    @Suppress("UNUSED_PARAMETER") articleId: String,
    audioUrl: String?,
    modifier: Modifier = Modifier,
    viewModel: OfflineCacheViewModel = hiltViewModel(),
) {
    if (audioUrl.isNullOrBlank()) return

    val state by viewModel.states.collectAsState()
    val current = remember(articleId, state) { state[articleId] ?: DownloadState.Idle }
    val isCached = viewModel.isCached(audioUrl)

    IconButton(onClick = { /* no-op: cache 透明生效 */ }, modifier = modifier) {
        if (isCached || current is DownloadState.Downloaded) {
            Icon(
                imageVector = Icons.Filled.CloudDone,
                contentDescription = "已缓存,离线可播",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = "播放时自动缓存",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Hilt ViewModel: 暴露缓存状态查询。
 */
@HiltViewModel
class OfflineCacheViewModel @Inject constructor(
    private val offlineDownloadManager: OfflineDownloadManager,
) : ViewModel() {
    val states = offlineDownloadManager.states

    fun isCached(audioUrl: String): Boolean = offlineDownloadManager.isCached(audioUrl)
}