package com.tingxia.audio.ui.favorites

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.remote.Favorite
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var favoriteToDelete by remember { mutableStateOf<Favorite?>(null) }

    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) {
            isRefreshing = false
        }
    }

    // ── 三态分离（2026-10-27）────────────────────────────────────────
    //
    // 原来加载失败只弹一个 Toast，界面接着渲染「暂无收藏内容」⇒ 用户看到的是
    // "我没有收藏"，而不是"没加载出来"；Toast 还会自己消失 ⇒ 事后回头看，
    // 什么都判断不出来。
    //
    // 现在三种状态严格分开：
    //   · 加载中 → 转圈（还没加载完就说"暂无收藏内容"是骗人）
    //   · 加载失败 → 错误文案 + 一个**能点**的「重试」
    //   · 成功但为空 → 原来的空态文案（这才是真的"没收藏"）
    //
    // 判据用「error != null 且列表为空」：列表为空 ⇒ 用户此刻没有任何内容可看，
    // 这时再渲染空态文案就是把失败说成了"没有"；列表还在（刷新失败、删除失败）
    // ⇒ 内容照旧显示，错误走 Toast，不夺走用户的列表。
    val loadFailed = uiState.error != null && uiState.favorites.isEmpty()

    LaunchedEffect(uiState.error) {
        val message = uiState.error
        // 加载失败由常驻的错误态承载（见下），不再叠一个 Toast ——
        // Toast 会消失，用户回头就不知道刚才到底是失败还是"真的没收藏"。
        // 只有「列表还在、只是这次操作失败」（如删除失败）才走 Toast：轻量、不打断浏览。
        if (message != null && uiState.favorites.isNotEmpty()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val allTabs = listOf("全部") + uiState.folders.map { it.folder }
    val selectedFolder = if (selectedTabIndex == 0) null else allTabs.getOrNull(selectedTabIndex)

    LaunchedEffect(selectedTabIndex) {
        if (selectedTabIndex == 0) {
            viewModel.selectFolder(null)
        } else {
            viewModel.selectFolder(allTabs[selectedTabIndex])
        }
    }

    if (favoriteToDelete != null) {
        AlertDialog(
            onDismissRequest = { favoriteToDelete = null },
            title = { Text("删除收藏") },
            text = { Text("确定删除这篇收藏吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFavorite(favoriteToDelete!!.id)
                        favoriteToDelete = null
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { favoriteToDelete = null }) {
                    Text("取消")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的收藏") },
                // 2026-10-03：这一屏原来只有标题、没有任何返回入口。
                // 五个二级屏全都一样：进来的路是「从别处跳过来」，
                // 退回去的路只剩系统返回手势和底部系统导航的返回键。
                // 在手势导航机型上，系统返回条和 Home 指示条常常重叠成
                // 一个拇指热区，单手够不着，用户就走不出去了。
                // onBack 参数本来就在，只是没接上。
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
            if (allTabs.size > 1) {
                // ScrollableTabRow 而不是 TabRow（2026-10-27）：
                // 收藏夹多了以后 TabRow 会把标题挤成两行/省略号（"技术(12)"→"技…"），
                // 用户既数不清有几个夹子、也点不准要进的那一个。
                // 可横向滚动 ⇒ 标题保持完整，夹子再多也只是"多划一下"。
                ScrollableTabRow(selectedTabIndex = selectedTabIndex) {
                    allTabs.forEachIndexed { index, folder ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = {
                                val count = if (index == 0) {
                                    uiState.folders.sumOf { it.count }
                                } else {
                                    uiState.folders.find { it.folder == folder }?.count ?: 0
                                }
                                Text(if (count > 0) "$folder ($count)" else folder)
                            },
                        )
                    }
                }
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    viewModel.loadFavorites(selectedFolder)
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    uiState.isLoading && uiState.favorites.isEmpty() -> {
                        // 加载中：只转圈，不写"暂无收藏内容"—— 那是在替服务端撒谎。
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    loadFailed -> {
                        // 加载失败：说清是"没加载出来"，并给一个能点的重试。
                        LoadErrorState(
                            message = uiState.error ?: "加载失败",
                            onRetry = { viewModel.loadFavorites(selectedFolder) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    uiState.favorites.isEmpty() -> {
                        EmptyFavoritesState(
                            modifier = Modifier.fillMaxSize(),
                            onNavigateToList = onBack,
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(uiState.favorites, key = { it.id }) { favorite ->
                                FavoriteItem(
                                    favorite = favorite,
                                    onClick = { onNavigateToDetail(favorite.article_id) },
                                    onDelete = { favoriteToDelete = favorite },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 加载失败态：一句说清"是加载失败、不是没有内容"，外加一个**能点**的重试。
 *
 * 为什么不能只给文案：用户看到失败后唯一能做的事就是重试。
 * 没有按钮 ⇒ 用户只能退出重进，而 Toast 早就没了 ⇒ 这条错误等于没报过。
 *
 * 为什么不给图标：这一屏的错误态要表达的是"没加载出来"这件事，
 * 文案 + 按钮已经说全了，图标只是装饰（而且装饰性图标必须 contentDescription=null）。
 *
 * 按钮用 Material3 的 [Button]：它的最小可点区域由 minimumInteractiveComponentSize
 * 兜到 48dp（视觉高度 40dp，触摸区域 48dp），满足无障碍最小触达要求。
 */
@Composable
private fun LoadErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                // 前面这句是给用户的**判据**：界面上出现"加载失败"和"暂无收藏内容"
                // 两句完全不同的话，用户才分得清"是没数据"还是"是没加载出来"。
                text = "加载失败：$message",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "点「重试」重新拉一次收藏。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun EmptyFavoritesState(
    modifier: Modifier = Modifier,
    onNavigateToList: () -> Unit,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.FavoriteBorder,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "暂无收藏内容",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "在文章详情页点击爱心即可收藏",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNavigateToList) {
            Text("去听听")
        }
    }
}

@Composable
private fun FavoriteItem(
    favorite: Favorite,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = favorite.article_title?.takeIf { it.isNotBlank() } ?: favorite.article_id,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // P1-4：标题缺失时仍展示 article_id（缩短）作为副标识，便于用户区分。
                    if (favorite.article_title.isNullOrBlank()) {
                        Text(
                            text = favorite.article_id.take(12),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = favorite.folder,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = favorite.created_at.take(10),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!favorite.note.isNullOrBlank()) {
                    Text(
                        text = favorite.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
