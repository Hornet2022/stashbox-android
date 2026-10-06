package com.tingxia.audio.ui.laterlistens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.remote.LaterListen
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaterListensScreen(
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    viewModel: LaterListensViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) {
            isRefreshing = false
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("稍后听") },
                // 2026-10-03：这一屏原来只有标题、没有任何返回入口。
                // 五个二级屏全都一样：进来的路是「从别处跳过来」，
                // 退回去的路只剩系统返回手势和底部系统导航的返回键。
                // 在手势导航机型上，系统返回条和 Home 指示条常常重叠成
                // 一个拇指热区，单手够不着，用户就走不出去了。
                // onBack 参数本来就在，只是没接上。
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                viewModel.loadLaterListens()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                uiState.isLoading && uiState.laterListens.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                uiState.laterListens.isEmpty() -> {
                    EmptyLaterListensState(
                        modifier = Modifier.fillMaxSize(),
                        onNavigateToList = onBack,
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(uiState.laterListens, key = { it.id }) { laterListen ->
                            LaterListenItem(
                                laterListen = laterListen,
                                onPlayNow = { onNavigateToDetail(laterListen.article_id) },
                                onCancel = { viewModel.unsnooze(laterListen.article_id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LaterListenItem(
    laterListen: LaterListen,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = laterListen.article_title?.takeIf { it.isNotBlank() } ?: laterListen.article_id,
                    style = MaterialTheme.typography.titleSmall,
                    // 不限行数的话，一篇长标题的稍后听条目会把整张列表
                    // 撑成参差的一列，扫视节奏全断。限两行 + 省略号。
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatSnoozeTime(laterListen.snooze_until),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (laterListen.article_title.isNullOrBlank()) {
                    Text(
                        text = laterListen.article_id.take(12),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPlayNow) {
                    Text("立即听")
                }
                TextButton(onClick = onCancel) {
                    Text("取消", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun EmptyLaterListensState(
    modifier: Modifier = Modifier,
    onNavigateToList: () -> Unit,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Notifications,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "暂无稍后听内容",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "在文章详情页预约提醒，不错过想听的内容",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNavigateToList) {
            Text("去听听")
        }
    }
}

private fun formatSnoozeTime(snoozeUntil: String?): String {
    if (snoozeUntil == null) return "稍后听"
    return try {
        val dateTime = ZonedDateTime.parse(snoozeUntil)
        val now = ZonedDateTime.now()
        val diff = java.time.Duration.between(now, dateTime)

        when {
            diff.isNegative -> "已到时间"
            diff.toHours() < 1 -> "${diff.toMinutes()}分钟后"
            diff.toHours() < 24 -> "${diff.toHours()}小时后"
            diff.toDays() < 7 -> "${diff.toDays()}天后"
            else -> dateTime.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        }
    } catch (_: Exception) {
        snoozeUntil.take(16)
    }
}
