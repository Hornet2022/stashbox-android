package com.tingxia.audio.ui.notifications

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.tingxia.audio.ui.theme.NotificationReadBackground
import com.tingxia.audio.ui.theme.NotificationUnreadBackground
import com.tingxia.audio.ui.theme.NotificationUnreadDot
import com.tingxia.audio.ui.theme.TagDefault
import com.tingxia.audio.ui.theme.TagEntertainment
import com.tingxia.audio.ui.theme.TagFinance
import com.tingxia.audio.ui.theme.TagHistory
import com.tingxia.audio.ui.theme.TagScience
import com.tingxia.audio.ui.theme.TagSports
import com.tingxia.audio.ui.theme.TagTech
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.remote.Notification
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCenterScreen(
    onBack: () -> Unit,
    onNavigateToArticle: (String) -> Unit,
    viewModel: NotificationCenterViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.load(unreadOnly = false)
    }

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

    val tabs = listOf("全部", "未读")
    val displayedNotifications = if (selectedTab == 1) {
        uiState.notifications.filter { !it.read }
    } else {
        uiState.notifications
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通知") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            val count = if (title == "未读") uiState.notifications.count { !it.read } else 0
                            Text(if (count > 0) "$title ($count)" else title)
                        },
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    viewModel.load(unreadOnly = false)
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    uiState.isLoading && uiState.notifications.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    displayedNotifications.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("暂无通知", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(displayedNotifications, key = { it.id }) { notification ->
                                NotificationItem(
                                    notification = notification,
                                    onClick = {
                                        viewModel.markRead(notification.id)
                                        notification.deeplink?.let { deeplink ->
                                            val articleId = parseArticleId(deeplink)
                                            if (articleId != null) {
                                                onNavigateToArticle(articleId)
                                            }
                                        }
                                    },
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
private fun NotificationItem(
    notification: Notification,
    onClick: () -> Unit,
) {
    val backgroundColor = if (!notification.read) {
        NotificationUnreadBackground
    } else {
        NotificationReadBackground
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Unread dot or tag chip
            if (!notification.read) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(NotificationUnreadDot),
                )
            } else if (notification.tag_slug != null) {
                TagChip(tagSlug = notification.tag_slug)
            } else {
                Box(modifier = Modifier.size(8.dp))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = notification.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatTime(notification.created_at),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
                Text(
                    text = notification.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.DarkGray,
                )
            }
        }
    }
}

@Composable
private fun TagChip(tagSlug: String) {
    val color = tagSlugToColor(tagSlug)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = tagSlug,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

private fun tagSlugToColor(tagSlug: String): Color = when (tagSlug.lowercase()) {
    "tech" -> TagTech
    "science" -> TagScience
    "history" -> TagHistory
    "finance" -> TagFinance
    "sports" -> TagSports
    "entertainment" -> TagEntertainment
    else -> TagDefault
}

private fun parseArticleId(deeplink: String): String? {
    // deeplink format: stashbox://article/{id}
    return try {
        if (deeplink.startsWith("stashbox://article/")) {
            deeplink.removePrefix("stashbox://article/")
        } else null
    } catch (_: Exception) {
        null
    }
}

private fun formatTime(isoTime: String): String {
    return try {
        val dateTime = ZonedDateTime.parse(isoTime)
        val now = ZonedDateTime.now()
        val diff = java.time.Duration.between(dateTime, now)

        when {
            diff.toMinutes() < 1 -> "刚刚"
            diff.toMinutes() < 60 -> "${diff.toMinutes()}分钟前"
            diff.toHours() < 24 -> "${diff.toHours()}小时前"
            diff.toDays() < 7 -> "${diff.toDays()}天前"
            else -> dateTime.format(DateTimeFormatter.ofPattern("MM-dd"))
        }
    } catch (_: Exception) {
        isoTime.take(10)
    }
}
