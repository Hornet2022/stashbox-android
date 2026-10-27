package com.tingxia.audio.ui.feedback

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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.remote.FeedbackCategory
import com.tingxia.audio.data.remote.FeedbackItem
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackHistoryScreen(
    onBack: () -> Unit,
    viewModel: FeedbackHistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) {
            isRefreshing = false
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的反馈") },
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
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                viewModel.loadFeedbacks()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                uiState.isLoading && uiState.feedbacks.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                uiState.feedbacks.isEmpty() -> {
                    EmptyFeedbackHistoryState(
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
                        items(uiState.feedbacks, key = { it.id }) { feedback ->
                            FeedbackItemCard(feedback = feedback)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFeedbackHistoryState(
    modifier: Modifier = Modifier,
    onNavigateToList: () -> Unit,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            // 2026-10-27：原来用齿轮（Settings）表示「还没有反馈」——
            // 齿轮在用户心智里是「设置 / 偏好」，和「反馈」毫无关系，
            // 空态图标在说的是另一件事。换成意见气泡（Feedback），图标和文案对上。
            imageVector = Icons.Outlined.Feedback,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            // 2026-10-27：alpha 从 0.5 提到 0.7。
            // 0.5 合成后压米白底只有 2.29:1 —— 这图标淡到几乎看不见，
            // 空态会变成"只有两行字、上面一片空白"。
            // 0.7 → 3.42:1，正好过非文本档 3:1（R6.7）。
            // 它是纯装饰（contentDescription = null），按 WCAG 1.4.11
            // 不算"需要辨识的控件本体"，所以不追求 4.5:1。
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "还没有反馈",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "使用过程中遇到问题，欢迎提交反馈与建议",
            style = MaterialTheme.typography.bodyMedium,
            // 2026-10-27：原来写 onSurfaceVariant.copy(alpha = 0.7f)，
            // 浅色主题下实测对比度只有 3.82:1，低于正文要求的 4.5:1（R6.7）。
            // 去掉 alpha，直接用 onSurfaceVariant —— 正文本来就不该再压透明度。
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNavigateToList) {
            Text("去听听")
        }
    }
}

@Composable
private fun FeedbackItemCard(feedback: FeedbackItem) {
    val categoryLabel = FeedbackCategory.entries.find { it.apiValue == feedback.category }?.displayName
        ?: feedback.category

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = categoryLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (feedback.rating != null) {
                    // 2026-10-27 无障碍：原来 14dp 的星形图标 contentDescription = null，
                    // 读屏把这一整块评分当成「什么都没有」——
                    // 评分只靠图形承载，读屏用户完全拿不到「这篇我给了几分」（R6.1）。
                    // 现在把分数写进整行的 contentDescription；
                    // 星形图标本身仍是装饰性的（null），不会被重复播报。
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.semantics {
                            contentDescription = "评分 ${feedback.rating} 星"
                        },
                    ) {
                        repeat(feedback.rating) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            Text(
                text = feedback.content,
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = feedback.created_at.take(10),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
