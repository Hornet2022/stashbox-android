package com.tingxia.audio.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.remote.FolderCount
import com.tingxia.audio.data.repository.FavoritesRepository
import com.tingxia.audio.data.repository.FeedbackRepository
import com.tingxia.audio.ui.articles.ArticleDetailViewModel
import com.tingxia.audio.ui.articles.RetryState
import com.tingxia.audio.ui.components.AudioPlayerBar
import com.tingxia.audio.ui.components.SourceBadge
import com.tingxia.audio.ui.components.StatusBadge
import com.tingxia.audio.ui.feedback.FeedbackBottomSheet
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

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
    favoritesRepository: FavoritesRepository? = null,
    feedbackRepository: FeedbackRepository? = null,
    viewModel: ArticleDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val retryState by viewModel.retryState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val article = uiState.article
    val context = LocalContext.current

    var showFavoriteSheet by remember { mutableStateOf(false) }
    var showLaterListenSheet by remember { mutableStateOf(false) }
    var showFeedbackSheet by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<FolderCount>>(emptyList()) }

    LaunchedEffect(articleId) {
        viewModel.loadArticle(articleId)
    }

    if (showFavoriteSheet && favoritesRepository != null) {
        AddFavoriteBottomSheet(
            articleId = articleId,
            folders = folders,
            onFoldersLoaded = { folders = it },
            favoritesRepository = favoritesRepository,
            onDismiss = { showFavoriteSheet = false },
        )
    }

    if (showLaterListenSheet && favoritesRepository != null) {
        AddLaterListenBottomSheet(
            articleId = articleId,
            favoritesRepository = favoritesRepository,
            onDismiss = { showLaterListenSheet = false },
        )
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
            articleId = articleId,
            feedbackRepository = feedbackRepository,
            appVersion = appVersion,
            onDismiss = { showFeedbackSheet = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(article?.title ?: "详情") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                actions = {
                    if (favoritesRepository != null) {
                        IconButton(onClick = { showFavoriteSheet = true }) {
                            Icon(
                                imageVector = Icons.Filled.Favorite,
                                contentDescription = "收藏",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        TextButton(onClick = { showLaterListenSheet = true }) {
                            Text("稍后听")
                        }
                    }
                    if (feedbackRepository != null) {
                        IconButton(onClick = { showFeedbackSheet = true }) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "反馈",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
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
        Crossfade(
            targetState = if (uiState.isLoading && article == null) "loading" else if (uiState.error != null) "error" else "content",
            animationSpec = tween(300),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            label = "article_detail_fade",
        ) { state ->
            when (state) {
                "loading" -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                "error" -> {
                    ErrorHint(
                        modifier = Modifier,
                        message = uiState.error ?: "加载失败",
                        onBack = onBack,
                    )
                }
                else -> {
                    val articleNotNull = article!!
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = articleNotNull.title,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SourceBadge(source = articleNotNull.source)
                            StatusBadge(status = uiState.status)
                        }

                        if (articleNotNull.url.isNotEmpty()) {
                            TextButton(
                                onClick = { clipboardManager.setText(AnnotatedString(articleNotNull.url)) },
                            ) {
                                Text("复制原文链接：${articleNotNull.url}")
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

                        if (uiState.status == DistillStatus.FAILED) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(24.dp),
                            ) {
                                Text("这篇蒸馏失败了", style = MaterialTheme.typography.titleMedium)
                                Text("换个源试试？点击重新蒸馏", style = MaterialTheme.typography.bodyMedium)
                                Button(
                                    onClick = { viewModel.retryArticle(articleNotNull.id) },
                                    enabled = retryState !is RetryState.Loading,
                                ) {
                                    if (retryState is RetryState.Loading) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text("重试蒸馏")
                                }
                                if (retryState is RetryState.Error) {
                                    Text(
                                        text = (retryState as RetryState.Error).message,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFavoriteBottomSheet(
    articleId: String,
    folders: List<FolderCount>,
    onFoldersLoaded: (List<FolderCount>) -> Unit,
    favoritesRepository: FavoritesRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var selectedFolder by remember { mutableStateOf("default") }
    var note by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            onFoldersLoaded(favoritesRepository.listFolders())
        } catch (_: Exception) {
            // ignore
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("添加收藏", style = MaterialTheme.typography.titleLarge)

            Text("选择文件夹", style = MaterialTheme.typography.titleSmall)
            LazyColumn(
                modifier = Modifier.height(150.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(folders, key = { it.folder }) { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedFolder == folder.folder,
                                onClick = { selectedFolder = folder.folder },
                                role = Role.RadioButton,
                            )
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedFolder == folder.folder,
                            onClick = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${folder.folder} (${folder.count})")
                    }
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("笔记（可选）") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )

            Button(
                onClick = {
                    isSubmitting = true
                    scope.launch {
                        try {
                            favoritesRepository.addFavorite(
                                articleId = articleId,
                                folder = selectedFolder,
                                note = note.ifBlank { null },
                            )
                            onDismiss()
                        } catch (_: Exception) {
                            // silent fail
                        } finally {
                            isSubmitting = false
                        }
                    }
                },
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text("保存")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddLaterListenBottomSheet(
    articleId: String,
    favoritesRepository: FavoritesRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var selectedOption by remember { mutableStateOf("tonight") }
    var isSubmitting by remember { mutableStateOf(false) }

    val options = listOf(
        "tonight" to "今晚睡前",
        "tomorrow" to "明天",
        "weekend" to "本周末",
        "custom" to "自定义时间",
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("稍后听", style = MaterialTheme.typography.titleLarge)

            options.forEach { (value, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selectedOption == value,
                            onClick = { selectedOption = value },
                            role = Role.RadioButton,
                        )
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedOption == value,
                        onClick = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label)
                }
            }

            Button(
                onClick = {
                    isSubmitting = true
                    scope.launch {
                        try {
                            val snoozeUntil = when (selectedOption) {
                                "tonight" -> ZonedDateTime.now().plusHours(4).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                                "tomorrow" -> ZonedDateTime.now().plusDays(1).withHour(9).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                                "weekend" -> ZonedDateTime.now().plusDays(5).withHour(10).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                                else -> null
                            }
                            favoritesRepository.snooze(
                                articleId = articleId,
                                snoozeUntil = snoozeUntil,
                            )
                            onDismiss()
                        } catch (_: Exception) {
                            // silent fail
                        } finally {
                            isSubmitting = false
                        }
                    }
                },
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text("保存")
            }

            Spacer(Modifier.height(16.dp))
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
