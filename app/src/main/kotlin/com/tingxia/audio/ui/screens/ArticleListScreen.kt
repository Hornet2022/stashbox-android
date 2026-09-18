package com.tingxia.audio.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.repository.FeedbackRepository
import com.tingxia.audio.ui.articles.ArticleListViewModel
import com.tingxia.audio.ui.components.SourceBadge
import com.tingxia.audio.ui.components.StatusBadge
import com.tingxia.audio.ui.feedback.FeedbackBottomSheet

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
    onNavigateToTags: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToLaterListens: () -> Unit,
    onNavigateToFeedbackHistory: () -> Unit,
    feedbackRepository: FeedbackRepository? = null,
    viewModel: ArticleListViewModel = hiltViewModel(),
) {
    val articles by viewModel.articles.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current

    var showFeedbackSheet by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadArticles()
    }

    if (showFeedbackSheet && feedbackRepository != null) {
        val appVersion = remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (_: Exception) {
                "unknown"
            }
        }
        FeedbackBottomSheet(
            feedbackRepository = feedbackRepository,
            appVersion = appVersion,
            onDismiss = { showFeedbackSheet = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("听匣") },
                actions = {
                    // CP5.3-C: 标签订阅入口
                    IconButton(
                        onClick = onNavigateToTags,
                        modifier = Modifier.semantics { contentDescription = "标签订阅" },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FavoriteBorder,
                            contentDescription = "标签订阅",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // CP5.4-C: 通知中心入口
                    IconButton(
                        onClick = onNavigateToNotifications,
                        modifier = Modifier.semantics { contentDescription = "通知中心" },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "通知中心",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // CP5.5-B1: 收藏入口
                    IconButton(
                        onClick = onNavigateToFavorites,
                        modifier = Modifier.semantics { contentDescription = "我的收藏" },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "我的收藏",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // CP5.5-B1: 稍后听入口
                    TextButton(
                        onClick = onNavigateToLaterListens,
                        modifier = Modifier.semantics { contentDescription = "稍后听" },
                    ) {
                        Text("稍后听", style = MaterialTheme.typography.labelMedium)
                    }
                    // CP5.5-A3: 反馈入口
                    IconButton(
                        onClick = { showSettingsMenu = true },
                        modifier = Modifier.semantics { contentDescription = "设置与反馈" },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置与反馈",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    DropdownMenu(
                        expanded = showSettingsMenu,
                        onDismissRequest = { showSettingsMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("反馈与建议") },
                            onClick = {
                                showSettingsMenu = false
                                showFeedbackSheet = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("反馈历史") },
                            onClick = {
                                showSettingsMenu = false
                                onNavigateToFeedbackHistory()
                            },
                        )
                    }
                    // TODO(CP4.6): 添加按钮 → 跳转 D9 URL Scheme 收集页
                    TextButton(onClick = { /* 占位，CP4.6 才接 */ }) {
                        Text("添加")
                    }
                },
            )
        },
    ) { innerPadding ->
        Crossfade(
            targetState = if (isLoading && articles.isEmpty()) "loading" else if (articles.isEmpty()) "empty" else "content",
            animationSpec = tween(300),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            label = "article_list_fade",
        ) { state ->
            when (state) {
                "loading" -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                "empty" -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "暂无文章",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
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
    }
}

@Composable
private fun ArticleCard(article: Article, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
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
