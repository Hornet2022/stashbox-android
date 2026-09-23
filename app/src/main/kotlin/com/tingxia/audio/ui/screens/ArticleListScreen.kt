package com.tingxia.audio.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.tingxia.audio.util.formatRelativeTime

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
    // CP-TAG-FILTER：可由 TagSubscriptionScreen 跳过来携带 tag=slug
    initialTag: String? = null,
    feedbackRepository: FeedbackRepository? = null,
    viewModel: ArticleListViewModel = hiltViewModel(),
) {
    val articles by viewModel.articles.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val activeTag by viewModel.activeTag.collectAsState()
    val deletingId by viewModel.deletingId.collectAsState()
    val context = LocalContext.current

    var showFeedbackSheet by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    // CP-DELETE: 长按卡片 → 删除确认
    var deleteTarget by remember { mutableStateOf<Article?>(null) }

    // CP-TAG-FILTER：初始 tag 来自 TagSubscriptionScreen 跳转（仅消费一次）
    val initialConsumed = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialTag) {
        if (!initialConsumed.value && initialTag != null) {
            initialConsumed.value = true
            viewModel.setActiveTag(initialTag)
        }
    }

    // CP-DELETE: 错误提示（含删除失败）走 Toast，轻量不打断浏览
    LaunchedEffect(error) {
        if (!error.isNullOrBlank()) {
            android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    if (deleteTarget != null) {
        val target = deleteTarget!!
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
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // CP-TAG-FILTER：active tag 时显示顶部 chip，"仅看 X ✕ 清除"
            activeTag?.let { tag ->
                ActiveTagChip(
                    tag = tag,
                    count = articles.size,
                    onClear = { viewModel.setActiveTag(null) },
                )
            }
            Crossfade(
                targetState = if (isLoading && articles.isEmpty()) "loading" else if (articles.isEmpty()) "empty" else "content",
                animationSpec = tween(300),
                modifier = Modifier.fillMaxSize(),
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
                                isDeleting = deletingId == article.id,
                                onClick = { onNavigateToDetail(article.id) },
                                onLongClick = { deleteTarget = article },
                            )
                        }
                    }
                }
            }
            }  // Crossfade 闭
        }  // Column 闭
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ArticleCard(
    article: Article,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isDeleting: Boolean = false,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                // CP-DELETE: 长按卡片 → 删除确认（避免误触，无滑动删除手势的依赖）
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = article.title ?: "无标题",
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
                if (isDeleting) {
                    Text(
                        "删除中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // CP-TIME：列表卡片加创建时间（用户问「这个什么时候加的」一眼能答）
            val relative = formatRelativeTime(article.createdAt)
            if (relative.isNotBlank()) {
                Text(
                    text = "剪藏于 $relative",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * CP-TAG-FILTER：active tag 时的顶部状态条。
 * 显示当前过滤的 slug + 文章数 + 清除按钮。
 * 设计：相比 AssistChip 更显眼的"软提示"，让用户清楚当前看到的是 tag 过滤结果而非全量。
 */
@Composable
private fun ActiveTagChip(tag: String, count: Int, onClear: () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = "仅看标签：$tag",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "($count 篇)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            TextButton(
                onClick = onClear,
            ) {
                Text("清除", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
