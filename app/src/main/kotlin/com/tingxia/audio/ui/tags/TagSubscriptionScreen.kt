package com.tingxia.audio.ui.tags

import android.widget.Toast
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagSubscriptionScreen(
    onBack: () -> Unit,
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
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tag.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = isSubscribed,
                onCheckedChange = { onToggle() },
            )
        }
    }
}
