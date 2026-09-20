package com.tingxia.audio.ui.capture

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.model.Article

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
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var inputValue by remember { mutableStateOf(TextFieldValue(uiState.url)) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
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
                            RecentArticleItem(article = article)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentArticleItem(article: Article) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = article.title ?: "无标题",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${article.source ?: "unknown"} · ${article.status ?: "pending"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

