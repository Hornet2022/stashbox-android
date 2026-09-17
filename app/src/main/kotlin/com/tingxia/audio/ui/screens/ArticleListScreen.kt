package com.tingxia.audio.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.ui.articles.ArticleListViewModel
import com.tingxia.audio.ui.components.SourceBadge
import com.tingxia.audio.ui.components.StatusBadge

/**
 * 文章列表页。
 *
 * - 顶部 TopAppBar：标题「听匣」+ 添加按钮（占位，CP4.6 才接 D9 收集页）
 * - 主体 LazyColumn：文章卡片列表（标题 + 来源 + 状态）
 * - 点击卡片 → [onNavigateToDetail]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleListScreen(
    onNavigateToDetail: (String) -> Unit,
    viewModel: ArticleListViewModel = hiltViewModel(),
) {
    val articles by viewModel.articles.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadArticles()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("听匣") },
                actions = {
                    // TODO(CP4.6): 添加按钮 → 跳转 D9 URL Scheme 收集页
                    TextButton(onClick = { /* 占位，CP4.6 才接 */ }) {
                        Text("添加")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (isLoading && articles.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(articles, key = { it.id }) { article ->
                    ArticleCard(
                        article = article,
                        onClick = { onNavigateToDetail(article.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArticleCard(article: Article, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceBadge(source = article.source)
                StatusBadge(status = article.status)
            }
        }
    }
}
