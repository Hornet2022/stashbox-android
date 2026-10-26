package com.tingxia.audio.ui.tags

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.model.KNOWN_TAG_CATEGORIES
import com.tingxia.audio.data.model.Tag
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagSubscriptionScreen(
    onBack: () -> Unit,
    // CP-TAG-FILTER：点击 tag 行 → 跳 ArticleListScreen?tag=slug
    onTagClick: (String) -> Unit = {},
    // 2026-10-23：订阅闭环的「读回」一环 → 点订阅更新里的文章进详情页
    onArticleClick: (String) -> Unit = {},
    viewModel: TagSubscriptionViewModel = hiltViewModel(),
    feedViewModel: SubscriptionFeedViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val feedState by feedViewModel.uiState.collectAsStateWithLifecycle()
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
                // 2026-10-03：补下拉刷新。
                // 通知 / 我的收藏 / 稍后听 / 我的反馈 四个列表都有 PullToRefreshBox，
                // 只有这一屏没有。可这一屏恰恰是最需要刷新的：订阅状态是
                // 别的设备 / 后台改的，用户切回来看到的还是旧的开关状态，
                // 而开关一拨是真实写库的，状态错了会直接误导操作。
                var isRefreshing by remember { mutableStateOf(false) }
                PullToRefreshBox(
                    // loadTags() 内部会把 isLoading 置 true 再落回 false，
                    // 这里额外等它归零才收指示器，避免刷新动画一闪而过。
                    isRefreshing = isRefreshing && uiState.isLoading,
                    onRefresh = {
                        isRefreshing = true
                        viewModel.loadTags()
                        feedViewModel.load()
                    },
                    modifier = Modifier.padding(innerPadding),
                ) {
                    // 2026-10-23：订阅内容流放在**标签列表上方** ——
                    // 订阅开关与「订阅带来的内容」同屏，闭环一眼可见。
                    //
                    // 为什么不另开一个页面：订阅此前只有「写」没有「读回」，
                    // 用户拨了开关却**看不到任何回报**（内测反馈「订阅功能好像
                    // 没有功能闭环」）。同屏放才能立刻回答「订阅有什么用」。
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "feed_section") {
                            SubscriptionFeedSection(
                                state = feedState,
                                onArticleClick = onArticleClick,
                            )
                        }
                        item(key = "tags_header") {
                            Text(
                                text = "全部标签",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        tagItems(uiState.tags, uiState.subscribedIds, viewModel::toggleTag, onTagClick)
                    }
                }
            }
        }
    }
}

/** 把标签按 category 分组渲染成 LazyColumn 的 items（原来在 TagList 里）。 */
private fun LazyListScope.tagItems(
    tags: List<Tag>,
    subscribedIds: Set<String>,
    onToggle: (String) -> Unit,
    onTagClick: (String) -> Unit,
) {
    val groupedTags: Map<String, List<Tag>> = tags.groupBy { tag -> tag.category }
    groupedTags.forEach { entry ->
        val category = entry.key
        val categoryTags = entry.value
        // CP-TAG-CATEGORY：优先用服务端吐的可读分组名 category_label，
        // 旧服务端没有该字段时用 KNOWN_TAG_CATEGORIES 兜底，最后才回退到原文。
        val headerText = categoryTags.firstOrNull()?.category_label
            ?: KNOWN_TAG_CATEGORIES[category]
            ?: category
        item(key = "header_$category") {
            Text(
                text = headerText,
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

/**
 * 「订阅更新」区块（2026-10-23）。
 *
 * 这是订阅闭环的**读回**一环。之前订阅只有开关、没有回报 ——
 * 用户拨了开关却看不到任何东西，内测因此反馈「订阅功能没有闭环」。
 */
@Composable
private fun SubscriptionFeedSection(
    state: SubscriptionFeedUiState,
    onArticleClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "订阅更新",
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.items.isNotEmpty()) {
                Text(
                    text = "${state.items.size} 篇",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            state.isLoading && state.items.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.items.isEmpty() -> {
                // 空态要说清「订阅之后会在这里出现什么」——
                // 否则用户看到空白会以为订阅没生效。
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (state.subscribedTags.isEmpty()) {
                            "还没有订阅任何标签。\n订阅之后，相关的新文章会出现在这里。"
                        } else {
                            "已订阅 ${state.subscribedTags.joinToString("、")}。\n" +
                                "有匹配的新文章时会出现在这里 —— 现在还没有。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            else -> {
                state.items.forEach { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clickable { onArticleClick(item.article_id) },
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            val meta = buildString {
                                if (item.tags.isNotEmpty()) {
                                    append(item.tags.joinToString(" · "))
                                }
                                if (item.duration_sec != null && item.duration_sec > 0) {
                                    if (isNotEmpty()) append(" · ")
                                    append("${item.duration_sec / 60}分${item.duration_sec % 60}秒")
                                }
                            }
                            if (meta.isNotEmpty()) {
                                Text(
                                    text = meta,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * 「订阅」开关（2026-10-26 修复）。
 *
 * 原来这里是一个**没有任何文字说明的裸 Switch** —— 内测用户（uid=23）反馈
 * 「看不到这篇文章」，查下来根因是**没订阅标签**：订阅按钮是个无名开关，
 * 用户看不出那是订阅入口，于是从没拨过它 → 订阅 feed 永远是空的。
 *
 * 改成带文字的订阅控件：
 * - 未订阅：outlined「订阅」，一眼看出这是可点的动作；
 * - 已订阅：filled + ✓「已订阅」，状态直白可辨。
 * 用户不必猜这个开关是干嘛的。
 */
@Composable
private fun TagItem(
    tag: Tag,
    isSubscribed: Boolean,
    onToggle: () -> Unit,
    // CP-TAG-FILTER：点击 card → onTagClick 跳文章流（仅替换 tag id）
    onClick: () -> Unit = {},
) {    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tag.name,
                    style = MaterialTheme.typography.bodyLarge,
                )
                // ⚠️ 文案要说准（2026-10-23）：这里统计的是**该用户自己**蒸馏的
                // 文章数（后端 `Article.user_id == uid`），**不是**「订阅这个标签
                // 能带来多少篇」。原来写「N 篇已蒸馏」放在订阅开关旁边，
                // 用户会当成「订阅能收到 N 篇」→ 订阅完发现没有，就成了
                // 「订阅没用」的错觉（内测反馈「订阅没有闭环」的一部分）。
                // 改成明确的「你已蒸馏 N 篇」。
                if (tag.article_count > 0) {
                    Text(
                        text = "你已蒸馏 ${tag.article_count} 篇",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 订阅动作：带文字、带状态色的 Chip，替代原来的无名 Switch。
            FilterChip(
                selected = isSubscribed,
                onClick = onToggle,
                label = {
                    Text(
                        text = if (isSubscribed) "已订阅" else "订阅",
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                leadingIcon = if (isSubscribed) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}
