package com.tingxia.audio.ui.offline

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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 通勤预加载页（CP7.4.0 §3.2 客户端入口）。
 *
 * 入口：从首页/设置/订阅页跳进来。
 * 行为：对「稍后听」列表批量调 §3.2 warm，让服务端按需转码 64k 档；
 * 转码结果落到 ExoPlayer CacheDataSource（首播即命中）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineDownloadScreen(
    onBack: () -> Unit,
    viewModel: OfflineDownloadViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通勤预加载") },
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
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "把稍后听列表预热到本机",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "在 Wi-Fi 环境批量转码 64k 档（流量友好），通勤无网时也能秒开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (uiState.isBatching) {
                        LinearProgressIndicator(
                            progress = { uiState.progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                // 进度条自己只会读成"进度条，30%"，
                                // 用户不知道这是在预热第几篇。
                                // 描述要说**进度**（正在预热 3/10），不是"进度条"。
                                .semantics {
                                    contentDescription =
                                        "正在预热 ${uiState.progressIndex}/${uiState.progressTotal}"
                                },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${uiState.progressIndex} / ${uiState.progressTotal}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Button(
                            onClick = { viewModel.batchWarm() },
                            enabled = !uiState.isLoading && uiState.candidates.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                // 加载失败时不能写"开始预热（0 篇）"——
                                // "0 篇"是在说"没有文章"，而实际是**没读到**。
                                // 这两句话对用户的意义完全不同（见下面的错误态）。
                                if (uiState.error != null) {
                                    "暂时预热不了，稍后重试"
                                } else {
                                    "开始预热（${uiState.candidates.size} 篇）"
                                }
                            )
                        }
                    }
                }
            }

            // ── 三态分离（2026-10-27）────────────────────────────────────
            //
            // 原来加载失败弹一个对话框，而对话框背后的正文写的是
            // 「稍后听列表为空」⇒ 用户关掉弹窗，看到的是"你没加过文章"，
            // 而不是"没加载出来"。而且弹窗一关，错误就彻底没了。
            //
            // 现在三种状态严格分开：
            //   · 加载中 → 转圈
            //   · 加载失败 → 错误文案 + 一个**能点**的「重试」
            //   · 成功但为空 → 原来的空态文案（这才是真的"没加过"）
            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                uiState.error != null -> {
                    // 这一屏的 error 只在加载稍后听列表失败时出现
                    // （VM 的 batchWarm 不写 error，只在完成汇总里报失败数），
                    // 所以它就是"加载失败"，可以直接重试加载。
                    LoadErrorState(
                        message = uiState.error ?: "加载失败",
                        onRetry = { viewModel.load() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                uiState.candidates.isEmpty() -> {
                    Text(
                        "稍后听列表为空。先在详情页把想听的文章加到「稍后听」再来。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Text(
                        "候选列表",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // progressIndex 是 1 基的（第几篇正在处理）⇒ 换算成 0 基下标。
                        val currentIndex = uiState.progressIndex - 1
                        itemsIndexed(uiState.candidates, key = { _, c -> c.articleId }) { index, candidate ->
                            // 逐条状态：以前每行只读得出标题，
                            // 听不出这一篇是"还没轮到"还是"正在转码"还是"这一轮已经过了"。
                            // ⚠️ VM 没记逐条结果（只记总数）⇒ 这里只能说"已处理"，
                            // 成功/失败的准确数字留给完成后的汇总弹窗。
                            val status = when {
                                !uiState.isBatching -> "待预热"
                                index < currentIndex -> "已处理"
                                index == currentIndex -> "正在预热"
                                else -> "待预热"
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                        // 合并成一格朗读：标题 + 这一条的状态。
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = "《${candidate.title}》，$status"
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.CloudDownload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        candidate.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 批量预热完成 summary
    val summary = uiState.batchSummary
    if (summary != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSummary() },
            title = { Text("预热完成") },
            text = {
                Column {
                    // 「已下载到本机」才是用户真正关心的数；「已转码」只是服务端有货。
                    // 原来只报 warmed（转码数）却配「到本机」的文案，等于报了个假成功。
                    Text("已下载到本机：${summary.downloaded}")
                    if (summary.transcoded > summary.downloaded) {
                        Text("已转码待下载：${summary.transcoded - summary.downloaded}")
                    }
                    Text("失败：${summary.failed}")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已下载的音频现在断网也能听；只转码未下载的，仍需联网播放一次才会缓存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissSummary() }) { Text("好的") }
            },
        )
    }
}

/**
 * 加载失败态：一句说清"是加载失败、不是没有内容"，外加一个**能点**的重试。
 *
 * 为什么不能只给文案：用户看到失败后唯一能做的事就是重试。
 * 没有按钮 ⇒ 用户只能退出重进，而弹窗早就关了 ⇒ 这条错误等于没报过。
 *
 * 按钮用 Material3 的 [Button]：最小可点区域由 minimumInteractiveComponentSize
 * 兜到 48dp（视觉高度 40dp，触摸区域 48dp）。
 */
@Composable
private fun LoadErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            // 这句话必须说"加载失败"而不是"列表为空"——
            // 用户没加过文章和没读到文章，后果完全不同（前者不用管，后者要重试）。
            text = "加载失败：$message",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "点「重试」重新读一次稍后听列表。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry) {
            Text("重试")
        }
    }
}
