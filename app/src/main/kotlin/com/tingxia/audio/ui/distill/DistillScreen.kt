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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.util.formatRelativeTime
import com.tingxia.audio.ui.components.sourceLabelRes
import com.tingxia.audio.ui.components.statusLabelRes
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val deletingId by viewModel.deletingId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // CP-DELETE：删除确认（pending/failed 文章可删）
    var deleteTarget by remember { mutableStateOf<Article?>(null) }

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这篇内容？") },
            text = {
                Text(
                    "「${target.title ?: "无标题"}」将从列表移除，蒸馏记录一并删除，不可恢复。",
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
                uiState.isLoading &&
                    uiState.pendingArticles.isEmpty() &&
                    uiState.distillingArticles.isEmpty() &&
                    uiState.failedArticles.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.pendingArticles.isEmpty() &&
                    uiState.distillingArticles.isEmpty() &&
                    uiState.failedArticles.isEmpty() -> {
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
                        // CP-DISTILL：蒸馏中心三段式（带 sticky 头）—— 用户能直观看到一篇任务走到哪一步
                        if (uiState.distillingArticles.isNotEmpty()) {
                            item(key = "header_distilling") {
                                SectionHeader(
                                    title = "蒸馏中（${uiState.distillingArticles.size}）",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            items(uiState.distillingArticles, key = { it.id }) { article ->
                                DistillItem(
                                    article = article,
                                    isDistilling = uiState.isDistilling,
                                    countdown = uiState.inProgress[article.id],
                                    isDeleting = deletingId == article.id,
                                    onDistill = { viewModel.distill(article.id) },
                                    onDelete = { deleteTarget = article },
                                    distillButtonText = "重新蒸馏",
                                )
                            }
                        }
                        if (uiState.pendingArticles.isNotEmpty()) {
                            item(key = "header_pending") {
                                SectionHeader(
                                    title = "等待蒸馏（${uiState.pendingArticles.size}）",
                                )
                            }
                            items(uiState.pendingArticles, key = { it.id }) { article ->
                                DistillItem(
                                    article = article,
                                    isDistilling = uiState.isDistilling,
                                    countdown = uiState.inProgress[article.id],
                                    isDeleting = deletingId == article.id,
                                    onDistill = { viewModel.distill(article.id) },
                                    onDelete = { deleteTarget = article },
                                )
                            }
                        }
                        if (uiState.failedArticles.isNotEmpty()) {
                            item(key = "header_failed") {
                                SectionHeader(
                                    title = "失败待重试（${uiState.failedArticles.size}）",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                            items(uiState.failedArticles, key = { it.id }) { article ->
                                DistillItem(
                                    article = article,
                                    isDistilling = uiState.isDistilling,
                                    countdown = uiState.inProgress[article.id],
                                    isDeleting = deletingId == article.id,
                                    onDistill = { viewModel.distill(article.id) },
                                    onDelete = { deleteTarget = article },
                                    distillButtonText = "重新蒸馏",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = tint,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun DistillItem(
    article: Article,
    isDistilling: Boolean,
    countdown: DistillViewModel.DistillInProgress?,
    isDeleting: Boolean = false,
    onDistill: () -> Unit,
    onDelete: () -> Unit,
    distillButtonText: String = "立即蒸馏",
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
                        // 原来是把枚举名小写直接印出来，用户看到的是
                        // 「公众号 · distilling」—— 一个全中文界面里冒出的
                        // 英文机器码。strings.xml 里早就有状态映射，
                        // StatusBadge 也在用，这里绕开了。统一走 statusLabelRes。
                        text = "${stringResource(sourceLabelRes(article.source))} · ${stringResource(statusLabelRes(article.status))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // CP-TIME：待蒸馏/失败列表的时间提示（pending 看到「何时加的」；
                    // failed 看到「articles.updated_at」即最近一次失败时间）
                    val timeLabel = when (article.status) {
                        com.tingxia.audio.data.model.DistillStatus.PENDING ->
                            formatRelativeTime(article.createdAt).let { if (it.isNotBlank()) "提交于 $it" else "" }
                        com.tingxia.audio.data.model.DistillStatus.FAILED ->
                            formatRelativeTime(article.updatedAt).let { if (it.isNotBlank()) "失败于 $it" else "" }
                        else -> ""
                    }
                    if (timeLabel.isNotBlank()) {
                        Text(
                            text = timeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(
                    onClick = onDistill,
                    // CP11.0.5 P3.2: 倒计时中 OR 派发中都 disable
                    enabled = !isDistilling && !isInProgress,
                ) {
                    Text(
                        when {
                            isInProgress -> "蒸馏中"
                            else -> distillButtonText
                        }
                    )
                }
                // CP-DELETE：行尾垃圾桶（正在删除中 disable + 状态文本变"删除中…"）
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

            // CP11.0.5 P3.2(修正): 真轮询中 — 不确定进度指示器（不再用假 30s 倒计时）
            if (isInProgress) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "蒸馏中（后台处理中…）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}