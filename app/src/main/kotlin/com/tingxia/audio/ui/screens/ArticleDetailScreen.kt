package com.tingxia.audio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.ui.articles.ArticleDetailViewModel
import com.tingxia.audio.ui.components.AudioPlayerBar
import com.tingxia.audio.ui.components.SourceBadge
import com.tingxia.audio.ui.components.StatusBadge

/**
 * 文章详情页。
 *
 * - 顶部 TopAppBar：返回 + 标题
 * - 主体：标题 + 来源 + 状态 + 原文链接（点击复制）
 * - 蒸馏状态轮询（[ArticleDetailViewModel] 内每 3 秒 GET distill/{task_id}）
 * - status == ready → 显示 [AudioPlayerBar] + audio_url
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleDetailScreen(
    articleId: String,
    onBack: () -> Unit,
    viewModel: ArticleDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val article = uiState.article

    LaunchedEffect(articleId) {
        viewModel.loadArticle(articleId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(article?.title ?: "详情") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
            )
        },
        bottomBar = {
            if (uiState.status == DistillStatus.READY && article != null) {
                AudioPlayerBar(
                    title = article.title,
                    audioUrl = uiState.audioUrl,
                )
            }
        },
    ) { innerPadding ->
        when {
            uiState.isLoading && article == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null -> {
                ErrorHint(
                    modifier = Modifier.padding(innerPadding),
                    message = uiState.error ?: "加载失败",
                    onBack = onBack,
                )
            }
            article != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = article.title,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceBadge(source = article.source)
                        StatusBadge(status = uiState.status)
                    }

                    if (article.url.isNotEmpty()) {
                        TextButton(
                            onClick = { clipboardManager.setText(AnnotatedString(article.url)) },
                        ) {
                            Text("复制原文链接：${article.url}")
                        }
                    }

                    if (uiState.status == DistillStatus.DISTILLING) {
                        Text(
                            text = "蒸馏中…（每 3 秒轮询）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (uiState.pollError != null) {
                        Text(
                            text = uiState.pollError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorHint(
    message: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onBack) { Text("返回") }
    }
}
