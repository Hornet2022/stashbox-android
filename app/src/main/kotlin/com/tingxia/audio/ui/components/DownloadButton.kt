// Media3 把大量播放/缓存 API 标为 @UnstableApi。
// 这些文件直接使用 ExoPlayer / SimpleCache / DownloadManager 等 unstable 声明，
// 必须显式 opt-in，否则 :app:lintDebug 报 UnsafeOptInUsageError 直接让 CI 失败。
//
// 注意用的是 **androidx.annotation.OptIn** 而不是 kotlin.OptIn：Media3 的
// UnstableApi 标的是 androidx 的 @RequiresOptIn，lint 认的是前者；写 Kotlin 的
// @OptIn 只会让 lint 在这行本身上再报一次（实测错误数 16 → 22）。
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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

    val states by viewModel.states.collectAsStateWithLifecycle()
    val state = remember(articleId, states) { states[articleId] }
    val cachedUrls by viewModel.cachedUrls.collectAsStateWithLifecycle()
    val isCached = remember(articleId, audioUrl, cachedUrls) { audioUrl in cachedUrls }
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

    /**
     * 已完整缓存的音频 URL 集合。
     *
     * 刻意做成 StateFlow 而不是每次组合期同步问一次：查缓存要读磁盘 + Room，
     * 放 `remember {}` 里是阻塞 IO；而上一版直接同步调 `Cache.isCached` 更是
     * 遇到未缓存的文章就抛 IllegalArgumentException，把整个详情页带崩。
     */
    private val _cachedUrls = MutableStateFlow<Set<String>>(emptySet())
    val cachedUrls: StateFlow<Set<String>> = _cachedUrls.asStateFlow()

    init {
        refreshCachedUrls()
    }

    private fun refreshCachedUrls() {
        viewModelScope.launch { _cachedUrls.value = offlineDownloadManager.cachedUrls() }
    }

    fun isCached(audioUrl: String): Boolean = audioUrl in _cachedUrls.value

    /** 立即下载（用户主动点的，要看到反馈，不能丢给 WorkManager 静默跑）。 */
    fun download(articleId: String, audioUrl: String, title: String?, durationSec: Int?) {
        viewModelScope.launch {
            offlineDownloadManager.download(articleId, audioUrl, title, durationSec)
            refreshCachedUrls()
        }
    }

    /** 交给 WorkManager 后台跑（列表页批量预加载用，不需要进度反馈）。 */
    fun prefetchInBackground(articleId: String, audioUrl: String, title: String?, durationSec: Int?) {
        scheduler.prefetchAudio(articleId, audioUrl, title, durationSec)
    }

    fun remove(articleId: String, audioUrl: String) {
        viewModelScope.launch {
            offlineDownloadManager.remove(articleId, audioUrl)
            // 2026-10-03：原来漏了这一行（download() 有）。OfflineDownloadManager.remove()
            // 只清自己的 cache/dao/_states，从不通知这个 VM 的 cachedUrls，
            // 于是文件已经删了、✓「已下载到本地」图标还留着，且在 VM 生命周期内
            // 永远回不去 —— 用户要么白下一遍，要么信这个 ✓ 然后进地铁发现打不开。
            refreshCachedUrls()
        }
    }
}
