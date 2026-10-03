package com.tingxia.audio.ui.capture

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.ui.components.QuotaBanner
import com.tingxia.audio.ui.components.sourceLabelRes
import com.tingxia.audio.util.formatRelativeTime
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.ui.components.statusLabelRes

/**
 * CP11.0.2 剪藏页:粘贴 URL → 后端自动创建文章 + 派蒸馏任务。
 *
 * - 上半部分:URL 输入框 + 立即剪藏按钮
 * - 下半部分:最近剪藏列表(20 条)
 * - 成功 → Toast 提示 + 列表自动刷新,**不自动 popBack**(让用户看到结果)
 * - 失败 → Toast 错误信息
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onBack: () -> Unit,
    onCaptured: (String) -> Unit,
    onQuotaExhausted: () -> Unit = {},
    onNavigateToDetail: (String) -> Unit = {},
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val deletingId by viewModel.deletingId.collectAsState()
    val context = LocalContext.current
    var inputValue by remember { mutableStateOf(TextFieldValue(uiState.url)) }
    // CP-DELETE：最近剪藏删除确认（与列表页/详情页同一交互语义）
    var deleteTarget by remember { mutableStateOf<Article?>(null) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    // CP11.0.4 P1.2: 配额用尽 → 跳 Paywall(一次性事件)
    LaunchedEffect(uiState.quotaExhausted) {
        if (uiState.quotaExhausted) {
            viewModel.consumeQuotaExhausted()
            onQuotaExhausted()
        }
    }

    // CP-DELETE：删除确认对话框
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这篇内容？") },
            text = {
                Text(
                    "「${target.title ?: "无标题"}」的蒸馏结果和音频将一并删除，不可恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        viewModel.deleteArticle(target.id)
                    },
                    enabled = deletingId == null,
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    // P0-4：屏幕停用时主动停掉 ViewModel 内的轮询，避免泄漏
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { viewModel.stopStatusPolling() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("剪藏文章") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // CP11.0.4 P1.2 + P3.1: 配额提示条(Low/Exhausted 才显示)
            if (uiState.quotaTotal != null) {
                QuotaBanner(
                    used = uiState.quotaUsed,
                    total = uiState.quotaTotal,
                    remaining = uiState.quotaRemaining,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }

            // 上半部分:URL 输入 + 立即剪藏
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "粘贴公众号/网页链接",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = inputValue,
                    onValueChange = {
                        inputValue = it
                        viewModel.onUrlChanged(it.text)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://mp.weixin.qq.com/s/...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    enabled = !uiState.isLoading,
                )

                Button(
                    onClick = {
                        viewModel.capture { articleId ->
                            Toast.makeText(
                                context,
                                "剪藏成功,正在蒸馏",
                                Toast.LENGTH_SHORT,
                            ).show()
                            viewModel.consumeCaptured()
                            onCaptured(articleId)
                        }
                    },
                    enabled = !uiState.isLoading && uiState.url.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("立即剪藏")
                    }
                }
            }

            HorizontalDivider()

            // 下半部分:最近剪藏列表
            Text(
                text = "最近剪藏",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )

            when {
                uiState.isRecentLoading && uiState.recentArticles.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                uiState.recentArticles.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "暂无剪藏记录",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(uiState.recentArticles, key = { it.id }) { article ->
                            RecentArticleItem(
                                article = article,
                                isDeleting = deletingId == article.id,
                                onDelete = { deleteTarget = article },
                                onClick = { onNavigateToDetail(article.id) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentArticleItem(
    article: Article,
    isDeleting: Boolean = false,
    onDelete: () -> Unit,
    onClick: () -> Unit = {},
) {
    // BUG#10（2026-09-30 自测）：这一行原先**没有任何点击处理**，
    // 整张卡片只有右上角垃圾桶可点 —— 「最近剪藏」长得就是个能点的列表，
    // 点了却毫无反应，是条死路。（其他列表页如收藏/稍后听都有 onNavigateToDetail，
    // 只有剪藏页漏了。）
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDeleting, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = article.title ?: "无标题",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    // 2026-10-03 真机发现：这里直接插值 article.status，插的是
                    // 枚举的 toString()，也就是机器码 —— 用户在「最近剪藏」里
                    // 看到的是「通用 · READY」。同款问题在 DistillScreen 修过一次
                    // （那里是 .name.lowercase()，显示 "distilling"），这一处漏了。
                    // ?: 那个 "pending" 更是连兜底都是没翻译的英文。
                    // 一律走 statusLabelRes —— 全 App 唯一的状态到中文的映射。
                    text = if (isDeleting) "删除中…" else
                        "${stringResource(sourceLabelRes(article.source))} · " +
                            stringResource(statusLabelRes(article.status ?: DistillStatus.PENDING)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDeleting) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // CP-TIME：剪藏时间（相对时间）—— 让用户一眼知道这篇是什么时候加的
                val relative = formatRelativeTime(article.createdAt)
                if (relative.isNotBlank()) {
                    Text(
                        text = "剪藏于 $relative",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // CP-DELETE：每行一个删除按钮（红色垃圾桶），点击弹确认框
            IconButton(
                onClick = onDelete,
                enabled = !isDeleting,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除文章",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

