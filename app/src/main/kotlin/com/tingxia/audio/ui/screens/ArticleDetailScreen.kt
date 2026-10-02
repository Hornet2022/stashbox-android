package com.tingxia.audio.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.ui.friendlyError
import com.tingxia.audio.ui.tts.TtsPreferenceViewModel
import com.tingxia.audio.ui.tts.VoicePickerSheet
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.audio.PlayerControllerEntryPoint
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.data.model.TtsVoiceBrief
import com.tingxia.audio.data.remote.FolderCount
import com.tingxia.audio.data.repository.FavoritesRepository
import com.tingxia.audio.data.repository.FeedbackRepository
import com.tingxia.audio.ui.articles.ArticleDetailViewModel
import com.tingxia.audio.ui.articles.DeleteState
import com.tingxia.audio.ui.articles.RetryState
import com.tingxia.audio.ui.components.AudioPlayerBar
import com.tingxia.audio.ui.components.BitrateSelectorSheet
import com.tingxia.audio.ui.components.DownloadButton
import com.tingxia.audio.ui.components.SourceBadge
import com.tingxia.audio.ui.components.StatusBadge
import com.tingxia.audio.ui.evaluation.EvaluationDialog
import com.tingxia.audio.ui.feedback.FeedbackBottomSheet
import com.tingxia.audio.util.formatRelativeTime
import dagger.hilt.android.EntryPointAccessors
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
    onOpenFullScreenPlayer: () -> Unit = {},
    favoritesRepository: FavoritesRepository? = null,
    feedbackRepository: FeedbackRepository? = null,
    viewModel: ArticleDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val retryState by viewModel.retryState.collectAsState()
    val deleteState by viewModel.deleteState.collectAsState()
    val shouldShowEvaluation by viewModel.shouldShowEvaluationDialog.collectAsState()
    val myRating by viewModel.myRating.collectAsState()
    val taskId by viewModel.taskId.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val article = uiState.article
    val context = LocalContext.current

    var showFavoriteSheet by remember { mutableStateOf(false) }
    var showLaterListenSheet by remember { mutableStateOf(false) }
    var showFeedbackSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showManualRatingDialog by remember { mutableStateOf(false) }
    var showBitrateSheet by remember { mutableStateOf(false) }
    // CP-TTS-VOICE：换音色 / 重新生成
    var showVoiceSheet by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }
    var regenerateTargetId by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf<List<FolderCount>>(emptyList()) }

    // §3 多码率弹窗需要 PlayerController（用于切档不重启）
    val bitratePlayerController = remember {
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                PlayerControllerEntryPoint::class.java,
            ).playerController()
        }.getOrNull()
    }

    LaunchedEffect(articleId) {
        viewModel.loadArticle(articleId)
    }

    // CP-DELETE: 删除成功 → 回列表（列表页 LaunchedEffect 自动刷新，删除项消失）
    LaunchedEffect(deleteState) {
        if (deleteState is DeleteState.Success) {
            showDeleteConfirm = false
            onBack()
        }
    }

    // CP3.7.0: 评分弹窗 — 完听后自动弹 + 顶部手动「评分」按钮触发
    //
    // 「已评过就不再自动弹」只针对**自动**引导：读回确认评过就不该再索评。
    // 手动入口必须始终可用 —— 已评过的人正需要它改分（☆ 图标此时显示"修改评分"）。
    // 真机回归踩到过：写成 `(shouldShowEvaluation || showManualRatingDialog) && !isRated`
    // 会把手动入口一起堵死，评过分之后按钮点了没反应。
    val autoPrompt = shouldShowEvaluation && !viewModel.isRated
    val effectiveShowEval = autoPrompt || showManualRatingDialog
    val currentTaskId = taskId  // delegated property → 缓存到 local val 解 smart-cast 限制
    if (effectiveShowEval && currentTaskId != null) {
        EvaluationDialog(
            taskId = currentTaskId,
            onDismiss = {
                viewModel.dismissEvaluationDialog()
                showManualRatingDialog = false
            },
            // 提交成功后详情页立刻显示"已评分 ★N"，不依赖下次进页面重新读回
            onSubmitted = { resp ->
                viewModel.markRated(
                    overallScore = resp.overallScore,
                    evaluationId = resp.id,
                    taskId = resp.taskId,
                )
            },
        )
    }

    // §3.1 码率选择弹窗
    if (showBitrateSheet && currentTaskId != null && bitratePlayerController != null) {
        BitrateSelectorSheet(
            taskId = currentTaskId,
            playerController = bitratePlayerController,
            onDismiss = { showBitrateSheet = false },
        )
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

    // CP-TTS-VOICE：换音色 + 用新音色重新生成这一篇。
    // 这是「回溯重跑」在 App 里**唯一可达的入口** —— 它需要文章上下文。
    if (showVoiceSheet) {
        VoicePickerSheet(
            currentArticleId = articleId,
            onDismiss = { showVoiceSheet = false },
            onRequestRegenerate = { targetArticleId ->
                showVoiceSheet = false
                showRegenerateConfirm = true
                regenerateTargetId = targetArticleId
            },
        )
    }

    // 重新生成确认框：实测单篇约 23 分钟，不能一键静默重跑
    if (showRegenerateConfirm && regenerateTargetId != null) {
        val ttsVm: TtsPreferenceViewModel = hiltViewModel()
        AlertDialog(
            onDismissRequest = { showRegenerateConfirm = false },
            title = { Text("用新音色重新生成？") },
            text = {
                Text(
                    "将用「${ttsVm.uiState.value.preference?.effectiveVoiceName ?: "系统默认音色"}」" +
                        "重新生成这一篇。\n\n" +
                        "耗时约 20 分钟，期间可以继续听旧音频；生成成功后才替换。不会重复扣配额。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = regenerateTargetId
                        showRegenerateConfirm = false
                        regenerateTargetId = null
                        if (id != null) {
                            coroutineScope.launch {
                                runCatching { ttsVm.regenerateWithCurrentVoice(id) }
                                    .onSuccess {
                                        Toast.makeText(
                                            context,
                                            "已加入队列，完成后自动替换",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    .onFailure {
                                        Toast.makeText(
                                            context,
                                            friendlyError(it, "重新生成失败"),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                            }
                        }
                    }
                ) { Text("开始生成") }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateConfirm = false }) { Text("取消") }
            },
        )
    }

    // CP-DELETE: 删除确认对话框（硬删除二次确认）
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = {
                if (deleteState !is DeleteState.Loading) showDeleteConfirm = false
            },
            title = { Text("删除这篇内容？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "蒸馏结果和音频将一并删除，不可恢复；已消耗的生成配额不返还。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (deleteState is DeleteState.Error) {
                        Text(
                            text = (deleteState as DeleteState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteArticle(articleId) },
                    enabled = deleteState !is DeleteState.Loading,
                ) {
                    if (deleteState is DeleteState.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("删除中…")
                    } else {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    enabled = deleteState !is DeleteState.Loading,
                ) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // 原来直接 `Text(article?.title)`，没有 maxLines/overflow。
                // 文章标题动辄三四十个字（少数派、Solidot 的正文标题），
                // 撑开之后会直接把左侧「返回」和右侧「收藏/稍后听/评分」挤出
                // 屏幕 —— 顶栏固定高度，溢出部分没有第二行可去。
                // 正文主体里本来就有完整标题（大号），顶栏这里只保留一行
                // 截断的上下文，滚动时还能认出自己在哪一篇。
                title = {
                    Text(
                        text = article?.title ?: "详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    // 其余 9 个屏（剪藏/设置/蒸馏/离线预加载…）统一用
                    // AutoMirrored 箭头图标，这里原来是唯一用文字「返回」的。
                    // 文字按钮宽约 60px，在长标题场景下会把本就紧张的顶栏
                    // 再切掉三分之一，正好是标题被挤爆的帮凶之一。
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
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
                    // CP3.7.0: 评分入口（音频就绪后可见）
                    if (uiState.status == DistillStatus.READY && uiState.audioUrl != null && taskId != null) {
                        IconButton(onClick = { showManualRatingDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = if (myRating?.isRated == true) "修改评分" else "评分",
                                tint = if (myRating?.isRated == true) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    }
                    // §3.1 码率切换入口
                    if (uiState.status == DistillStatus.READY && taskId != null) {
                        IconButton(onClick = { showBitrateSheet = true }) {
                            Icon(
                                imageVector = Icons.Filled.Speed,
                                contentDescription = "音质",
                                tint = MaterialTheme.colorScheme.primary,
                            )
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
                    // CP-TTS-VOICE：换音色 + 用新音色重新生成这一篇。
                    //
                    // 放在详情页而不是设置页，因为只有这里有**文章上下文** ——
                    // 「重新生成」重跑的是这一篇，设置页给不出 article_id。
                    // 自测时发现这个入口原先只在设置页可达（且参数没传），
                    // 导致整条回溯重跑链路在 App 里是死的。
                    if (uiState.status == DistillStatus.READY) {
                        IconButton(onClick = { showVoiceSheet = true }) {
                            Icon(
                                imageVector = Icons.Filled.RecordVoiceOver,
                                contentDescription = "换音色",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    // 离线下载：点一下真下载，已下载时点一下删除（2026-10-02 从装饰品改成可用）
                    if (uiState.status == DistillStatus.READY && uiState.audioUrl != null) {
                        DownloadButton(
                            articleId = articleId,
                            audioUrl = uiState.audioUrl,
                            title = uiState.article?.title,
                            durationSec = uiState.audioDurationSec,
                        )
                    }
                    // CP-DELETE: 删除入口
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "删除文章",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (uiState.status == DistillStatus.READY && article != null) {
                AudioPlayerBar(
                    title = article.title ?: "",
                    audioUrl = uiState.audioUrl,
                    // 播放器据此判定"这次播放属于哪篇文章"→ 完听上报 → 自动弹评分卡。
                    // 漏传会让听感评分彻底不触发（真机 listen_complete 长期为 0 的根因）。
                    articleId = article.id,
                    onClick = onOpenFullScreenPlayer,
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
                    val safeArticle = article ?: return@Crossfade
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = safeArticle.title ?: "",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SourceBadge(source = safeArticle.source)
                            StatusBadge(status = uiState.status)
                        }

                        // 评分闭环读侧：已评过就在头部留痕，而不是提交完什么都不剩
                        myRating?.takeIf { it.isRated }?.let { rated ->
                            Text(
                                text = buildString {
                                    append("你的听感评分：")
                                    append("★".repeat(rated.overallScore ?: 0))
                                    append("☆".repeat(5 - (rated.overallScore ?: 0)))
                                    rated.comment?.takeIf { it.isNotBlank() }?.let {
                                        append(" · ").append(it)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }

                        // CP3.7.0: 蒸馏质量分 + 标签（§1.3 新字段，详情页头部加一行摘要）
                        if (uiState.status == DistillStatus.READY) {
                            QualityTagsRow(
                                tags = uiState.tagsIfReady,
                                qualityScore = uiState.qualityScoreIfReady,
                            )
                        }

                        // CP-TIME：详情页头部时间三件套（剪藏 → 完成 → 收听位置，依次显示）
                        val capturedRel = formatRelativeTime(safeArticle.createdAt)
                        if (capturedRel.isNotBlank()) {
                            Text(
                                text = "剪藏于 $capturedRel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val distilledRel = formatRelativeTime(safeArticle.distilledAt)
                        if (distilledRel.isNotBlank() && uiState.status == DistillStatus.READY) {
                            Text(
                                text = "蒸馏完成于 $distilledRel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        val failedRel = formatRelativeTime(safeArticle.updatedAt)
                        if (failedRel.isNotBlank() && uiState.status == DistillStatus.FAILED) {
                            Text(
                                text = "最近失败于 $failedRel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        if (safeArticle.url.isNotEmpty()) {
                            Text(
                                text = safeArticle.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            TextButton(
                                onClick = { clipboardManager.setText(AnnotatedString(safeArticle.url)) },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("复制原文链接")
                            }
                        }

                        // CP-TTS-VOICE 溯源：这段音频是谁念的。
                        // 紧挨着上面的「换音色」按钮 —— 点之前先让人知道现在是谁在念，
                        // 重生成之后也能一眼看出换没换成功。
                        VoiceCreditRow(voice = safeArticle.ttsVoice)

                        // CP-DISTILL-TEXT：LLM 听感改写稿（整理后的正文）
                        DistilledScriptCard(scriptText = safeArticle.scriptText)

                        if (uiState.status == DistillStatus.DISTILLING) {
                            Text(
                                // 原来写「（每 3 秒轮询）」。实现是 15 秒，而且写死不变；
            // 用户在详情页盯着这一个字，技术实现变成了产品文案。
            // 蒸馏单篇约 12 分钟，告诉用户真实时长比告诉他轮询频率有用得多。
            text = "蒸馏中…约需 12 分钟",
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
                                    onClick = { viewModel.retryArticle(safeArticle.id) },
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
    val context = LocalContext.current
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
                        } catch (e: Exception) {
                            // 2026-10-03：原来 `// silent fail`，点「保存」毫无反馈。
                            // 与稍后听那处同型，一并补上。
                            Toast.makeText(
                                context,
                                friendlyError(e, "收藏失败"),
                                Toast.LENGTH_LONG,
                            ).show()
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
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    var selectedOption by remember { mutableStateOf("tonight") }
    var isSubmitting by remember { mutableStateOf(false) }

    // 2026-10-03：删掉「自定义时间」。选中它之后 snoozeUntil 走 `else -> null`，
    // 而 null 的语义是「不设定时」—— 用户选了一个看得见的选项，保存后
    // 文章根本不会出现在稍后听里，全程零提示。一个会静默说谎的选项比没有更糟。
    // 要加回来的前提是接一个真的时间选择器（DatePickerDialog / TimePicker），
    // 而不是让 now() 拼一个假时间。
    val options = listOf(
        "tonight" to "今晚睡前",
        "tomorrow" to "明天",
        "weekend" to "本周末",
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
                        } catch (e: Exception) {
                            // 2026-10-03：原来 `catch (_: Exception) { // silent fail }`。
                            // 用户点「保存」→ 转圈停了、弹窗还开着、界面零变化、无文案
                            // 无 Toast，连点几次都这样 —— 客观上就是个死按钮。
                            // 同文件的删除/重试都走 Toast + friendlyError，这里漏了。
                            Toast.makeText(
                                context,
                                friendlyError(e, "保存稍后听失败"),
                                Toast.LENGTH_LONG,
                            ).show()
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

/**
 * CP-TTS-VOICE 溯源行：「本期由 婷婷 朗读」。
 *
 * 为什么详情页必须有这一行：服务端把「这段音频是谁念的」写进了
 * `distilled_articles.tts_voice_id`，但**自测发现它只写不读** ——
 * 三端都看不到。于是「换音色 → 重新生成」这条闭环只闭环了一半：
 * 确认框承诺「将用音色 X」，生成完却无从判断到底换没换。
 *
 * `available=false`（音色已下架/删除）时**照样显示名字**，只多一个「已下架」后缀：
 * 这段音频确实是它合成的，藏掉名字等于让历史音频变成「来源不明」。
 */
@Composable
private fun VoiceCreditRow(voice: TtsVoiceBrief?) {
    if (voice == null || voice.name.isBlank()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.RecordVoiceOver,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (voice.available) "本期由 ${voice.name} 朗读" else "本期由 ${voice.name} 朗读（音色已下架）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * CP3.7.0：蒸馏质量分 + 标签（§1.3 新字段展示）。
 * qualityScore ∈ [0,10]，tags 是 LLM 自动生成的中文名标签（最多 5 个）。
 */
@Composable
private fun QualityTagsRow(tags: List<String>, qualityScore: Double?) {
    if (tags.isEmpty() && qualityScore == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (qualityScore != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "蒸馏质量 ${"%.1f".format(qualityScore)} / 10",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.take(5).forEach { tag ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            "#$tag",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * CP-DISTILL-TEXT：LLM 听感改写稿（"整理后的正文"）卡片。
 *
 * 排版取向：印刷感 / 留白。首段（hook）放大作导语，其余按空行分段，
 * 正文用舒适行高 + 段距，整体放进 surfaceVariant 圆角容器。
 * scriptText 为空（未蒸馏 / 旧数据）时不渲染。
 */
@Composable
private fun DistilledScriptCard(
    scriptText: String?,
    modifier: Modifier = Modifier,
) {
    if (scriptText.isNullOrBlank()) return
    val paragraphs = remember(scriptText) {
        scriptText.trim().split(Regex("\\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
    }
    if (paragraphs.isEmpty()) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(2.dp),
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "听感整理稿",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            paragraphs.forEachIndexed { index, para ->
                if (index == 0) {
                    // 导语：开场钩子，稍大、稍强调
                    Text(
                        text = para,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 28.sp,
                    )
                } else {
                    Text(
                        text = para,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                        lineHeight = 26.sp,
                    )
                }
            }
        }
    }
}
