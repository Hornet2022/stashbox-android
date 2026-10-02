package com.tingxia.audio.ui.tags

import android.widget.Toast
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.model.Tag
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagSubscriptionScreen(
    onBack: () -> Unit,
    // CP-TAG-FILTER：点击 tag 行 → 跳 ArticleListScreen?tag=slug
    onTagClick: (String) -> Unit = {},
    viewModel: TagSubscriptionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("主题标签") },
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
        when {
            uiState.isLoading && uiState.tags.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                TagList(
                    tags = uiState.tags,
                    subscribedIds = uiState.subscribedIds,
                    onToggle = viewModel::toggleTag,
                    onTagClick = onTagClick,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

@Composable
private fun TagList(
    tags: List<Tag>,
    subscribedIds: Set<String>,
    onToggle: (String) -> Unit,
    onTagClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val groupedTags: Map<String, List<Tag>> = tags.groupBy { tag -> tag.category }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        groupedTags.forEach { entry ->
            val category = entry.key
            val categoryTags = entry.value
            item(key = "header_$category") {
                Text(
                    text = category,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(
                count = categoryTags.size,
                key = { index -> categoryTags[index].id },
            ) { index ->
                val tag = categoryTags[index]
                TagItem(
                    tag = tag,
                    isSubscribed = subscribedIds.contains(tag.id),
                    onToggle = { onToggle(tag.id) },
                    onClick = { onTagClick(tag.id) },
                )
            }
        }
    }
}

@Composable
private fun TagItem(
    tag: Tag,
    isSubscribed: Boolean,
    onToggle: () -> Unit,
    // CP-TAG-FILTER：点击 card → onTagClick 跳文章流（仅替换 tag id）
    onClick: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tag.name,
                    style = MaterialTheme.typography.bodyLarge,
                )
                // CP-TAG-FILTER：展示该 tag 下已蒸馏文章数 —— 让"订阅了有什么用"具体可见
                if (tag.article_count > 0) {
                    Text(
                        text = "${tag.article_count} 篇已蒸馏",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = isSubscribed,
                onCheckedChange = { onToggle() },
            )
        }
    }
}
