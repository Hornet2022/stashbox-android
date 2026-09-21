package com.tingxia.audio.ui.distill

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.model.Article

/**
 * CP10.4 蒸馏中心:列出所有 PENDING / FAILED 文章,提供「立即蒸馏」按钮手动重派。
 *
 * CP11.0.5 P3.2 升级:用户点 "立即蒸馏" 后,该文章卡片显示:
 *   - 真倒计时进度条 30s → 0
 *   - 剩余秒数文本 "蒸馏中,剩余 23 秒"
 *   - 按钮 disable,防止重复点击
 *   - 倒计时结束自动从列表移除 + Toast "蒸馏完成"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DistillScreen(
    onBack: () -> Unit,
    onDistilled: () -> Unit = {},
    viewModel: DistillViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
            // 蒸馏完成的 toast 不再 popBack — 用户留在蒸馏中心看列表刷新
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("蒸馏中心") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                uiState.isLoading && uiState.pendingArticles.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.pendingArticles.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "没有待蒸馏的文章",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "试试在「剪藏」页加几篇文章",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.pendingArticles, key = { it.id }) { article ->
                            DistillItem(
                                article = article,
                                isDistilling = uiState.isDistilling,
                                // CP11.0.5 P3.2: 倒计时状态(per-article)
                                countdown = uiState.inProgress[article.id],
                                onDistill = { viewModel.distill(article.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DistillItem(
    article: Article,
    isDistilling: Boolean,
    countdown: DistillViewModel.DistillInProgress?,
    onDistill: () -> Unit,
) {
    val isInProgress = countdown != null
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = article.title ?: "无标题",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.padding(2.dp))
                    Text(
                        text = "${article.source} · ${article.status.name.lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onDistill,
                    // CP11.0.5 P3.2: 倒计时中 OR 派发中都 disable
                    enabled = !isDistilling && !isInProgress,
                ) {
                    Text(if (isInProgress) "蒸馏中" else "立即蒸馏")
                }
            }

            // CP11.0.5 P3.2: 倒计时进度条 + 剩余秒数
            if (isInProgress && countdown != null) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = {
                        // remaining 0 → progress 0; remaining 30 → progress 1.0
                        countdown.remaining.toFloat() / countdown.total.toFloat()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "蒸馏中,剩余 ${countdown.remaining} 秒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}