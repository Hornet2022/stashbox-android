package com.tingxia.audio.ui.playlist

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingxia.audio.data.local.PlaylistItemEntity

/**
 * 播放列表（2026-10-23 新增）。
 *
 * ## 为什么要有这一屏
 *
 * 之前的队列是「文章列表页**当前可见**的 ready 文章」——
 * 列表一刷新、翻一页、换个筛选，队列就变了。用户**看不见也管不了**，
 * 于是通知栏/耳机键的「上一首/下一首」常年是灰的
 * （Media3 的 `COMMAND_SEEK_TO_NEXT` 在单曲队列上不可用 —— 这符合它的语义，
 * 但对用户来说就是「我的播放器连着播不了下一集」）。
 *
 * 这一屏把「想连着听的一串」变成**看得见、管得了、退出重进还在**的东西。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    onBack: () -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeError()
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空播放列表？") },
            text = { Text("队列里的 ${uiState.items.size} 篇都会被移出，文章本身不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clear()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("播放列表${if (uiState.items.isNotEmpty()) "（${uiState.items.size}）" else ""}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (uiState.items.isNotEmpty()) {
                        TextButton(onClick = { showClearConfirm = true }) { Text("清空") }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                uiState.items.isEmpty() -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "播放列表还是空的",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "在文章列表或详情页点「加入播放列表」，" +
                            "攒好的一串就能连着播 —— 通知栏和耳机键的上下首也就能用了。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(uiState.items, key = { _, item -> item.articleId }) { index, item ->
                        PlaylistRow(
                            item = item,
                            canMoveUp = index > 0,
                            canMoveDown = index < uiState.items.lastIndex,
                            onPlay = { viewModel.playFrom(index) },
                            onRemove = { viewModel.remove(item.articleId) },
                            onMoveUp = { viewModel.moveUp(item.articleId) },
                            onMoveDown = { viewModel.moveDown(item.articleId) },
                        )
                    }
                }
            }
        }
    }
}

/** 播放列表的一行。 */
@Composable
private fun PlaylistRow(
    item: PlaylistItemEntity,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPlay) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "播放这一篇")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title?.takeIf { it.isNotBlank() } ?: "（无标题）",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatDuration(item.durationSec),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.DeleteOutline,
                contentDescription = "移出播放列表",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 秒 → m:ss。 */
private fun formatDuration(seconds: Int?): String {
    if (seconds == null || seconds <= 0) return "—"
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
