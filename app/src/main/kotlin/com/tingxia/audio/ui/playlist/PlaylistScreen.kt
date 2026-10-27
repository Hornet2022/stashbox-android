package com.tingxia.audio.ui.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingxia.audio.data.local.PlaylistItemEntity

/**
 * 播放列表（2026-10-23 新增）。
 *
 * ## 为什么要有这一屏
 *
 * 之前的队列是「文章列表页**当前可见**的 ready 文章」——
 * 列表一刷新、翻一页、换个筛选，队列就变了。用户**看不见也管不了**，
 * 于是通知栏/耳机键的「上一首/下一首」常年是灰的
 * （Media3 的 `COMMAND_SEEK_TO_NEXT` 在单曲队列上不可用 —— 这符合它的语义，
 * 但对用户来说就是「我的播放器连着播不了下一集」）。
 *
 * 这一屏把「想连着听的一串」变成**看得见、管得了、退出重进还在**的东西。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    onBack: () -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showClearConfirm by remember { mutableStateOf(false) }

    // ── 错误不再只靠一个 Toast（2026-10-27）────────────────────────────
    //
    // 原来这一屏的所有错误（移出失败/排序失败/清空失败/这篇还没音频）
    // 都只弹一个 Toast ⇒ Toast 几秒后自己消失，用户回头看这一屏
    // 什么都不知道（"刚才那一下到底移出成功没有？"）。
    //
    // 现在错误是**常驻**的：列表还在 ⇒ 错误显示在列表上方（不夺走列表，
    // 用户还得接着操作这一串）；列表空 ⇒ 整屏错误态。
    // 而且整屏错误态**不渲染**"播放列表还是空的"——
    // 失败不是"没有内容"，把失败说成空 = 把失败藏起来。
    //
    // 「重试」必须有**真东西**可重试，所以把最近一次的动作记下来；
    // 否则它只是个把提示关掉的按钮 —— 那正是「按键无效」本尊。
    var retryAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // 所有会写操作的动作都走这里：记下"怎么再做一遍"给「重试」用，
    // 并在新动作开始前清掉上一条错误（用户已经动手了，旧提示不该还挂着）。
    fun attempt(action: () -> Unit) {
        retryAction = action
        viewModel.consumeError()
        action()
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空播放列表？") },
            text = { Text("队列里的 ${uiState.items.size} 篇都会被移出，文章本身不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    attempt { viewModel.clear() }
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("播放列表${if (uiState.items.isNotEmpty()) "（${uiState.items.size}）" else ""}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (uiState.items.isNotEmpty()) {
                        TextButton(onClick = { showClearConfirm = true }) { Text("清空") }
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
            // 列表还在 ⇒ 错误以横幅的形式待在列表上方，不夺走用户的列表。
            if (uiState.error != null && uiState.items.isNotEmpty()) {
                PlaylistErrorBanner(
                    message = uiState.error ?: "操作失败",
                    onRetry = { retryAction?.let { attempt(it) } },
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                when {
                    uiState.isLoading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )

                    // 列表空 + 有错误 ⇒ 是"没成功"，不是"没内容"。
                    //
                    // ⚠️ 今天的实际情况：播放列表是**本机 Room 数据**，
                    // PlaylistViewModel 只在**操作**失败时写 error，
                    // 而操作失败时列表一定还在（所以走上面那条横幅）。
                    // ⇒ 这一支今天是**护栏**：万一以后 VM 把"读列表失败"也写进 error，
                    // 这里挡住的是最坏的一种骗人（读取失败却让用户看见"播放列表还是空的"）。
                    //
                    // ⚠️ 已知缺口：VM 没有 reload 入口（读列表的 Flow 在 init 里只收一次），
                    // 所以这一支的「重试」只能重做**最近一次动作**；
                    // 真正的"重新读取本机列表"要等 VM 补一个 reload()（已在汇报里列出）。
                    uiState.items.isEmpty() && uiState.error != null -> PlaylistLoadErrorState(
                        message = uiState.error ?: "加载失败",
                        onRetry = { retryAction?.let { attempt(it) } },
                        modifier = Modifier.align(Alignment.Center),
                    )

                    uiState.items.isEmpty() -> Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "播放列表还是空的",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "在文章列表或详情页点「加入播放列表」，" +
                                "攒好的一串就能连着播 —— 通知栏和耳机键的上下首也就能用了。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp,
                            vertical = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(uiState.items, key = { _, item -> item.articleId }) { index, item ->
                            PlaylistRow(
                                item = item,
                                canMoveUp = index > 0,
                                canMoveDown = index < uiState.items.lastIndex,
                                onPlay = { attempt { viewModel.playFrom(index) } },
                                onRemove = { attempt { viewModel.remove(item.articleId) } },
                                onMoveUp = { attempt { viewModel.moveUp(item.articleId) } },
                                onMoveDown = { attempt { viewModel.moveDown(item.articleId) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 列表还在时用的错误横幅：一句说清是什么没成功 + 一个能点的「重试」。
 *
 * 为什么不用 errorContainer 之外的底色：错误色 + onErrorContainer 是 Material3
 * 规定的错误配色（前景必须用对应的 on 角色）。
 */
@Composable
private fun PlaylistErrorBanner(
    message: String,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            // TextButton 的最小可点区域同样被 minimumInteractiveComponentSize
            // 兜到 48dp（视觉高度 40dp）。
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/**
 * 整屏错误态：说清"是没加载出来、不是没有内容"，外加一个**能点**的重试。
 *
 * 没有按钮 ⇒ 用户只能退出重进 ⇒ 这条错误等于没报过。
 * 按钮用 Material3 的 [Button]：最小可点区域由 minimumInteractiveComponentSize
 * 兜到 48dp（视觉高度 40dp，触摸区域 48dp）。
 */
@Composable
private fun PlaylistLoadErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            // 界面上的这句话必须是"加载失败"而不是"播放列表还是空的"——
            // 后者会让用户以为自己一篇都没加过（其实是没读到）。
            text = "加载失败：$message",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "点「重试」再试一次。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry) {
            Text("重试")
        }
    }
}

/** 播放列表的一行。 */
@Composable
private fun PlaylistRow(
    item: PlaylistItemEntity,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val title = item.title?.takeIf { it.isNotBlank() } ?: "（无标题）"
    // 读屏描述里必须带条目标题：一屏几十个"上移/下移/移出"按钮全都同名时，
    // 用户根本分不清自己在操作哪一篇。标题太长会拖慢读屏 ⇒ 截断到 20 字。
    val spokenTitle = remember(title) {
        if (title.length > 20) title.take(20) + "…" else title
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPlay) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "播放《$spokenTitle》")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatDuration(item.durationSec),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ── 行尾只留「更多」（2026-10-27）──────────────────────────────
        //
        // 原来播放/上移/下移/移出四个键并排 ⇒ 紧凑屏上标题只剩约 136dp，
        // 长标题基本读不了（Material 指南：一行里的动作按钮越少越好，
        // 次要动作收进溢出菜单）。
        //
        // 「移出」现在在菜单里，而且**不是只靠长按**：行尾这个「更多」按钮
        // 就是长按的等价替代路径（读屏用户与手指不灵活的用户都够得着）。
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "《$spokenTitle》的更多操作",
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("上移") },
                    leadingIcon = {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                    },
                    enabled = canMoveUp,
                    // 描述里带上条目标题：菜单项在读屏里是各自独立的焦点，
                    // 不带标题就又变成一串同名按钮。
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "上移《$spokenTitle》"
                    },
                    onClick = {
                        menuExpanded = false
                        onMoveUp()
                    },
                )
                DropdownMenuItem(
                    text = { Text("下移") },
                    leadingIcon = {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                    },
                    enabled = canMoveDown,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "下移《$spokenTitle》"
                    },
                    onClick = {
                        menuExpanded = false
                        onMoveDown()
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("移出播放列表") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "移出《$spokenTitle》"
                    },
                    onClick = {
                        menuExpanded = false
                        onRemove()
                    },
                )
            }
        }
    }
}

/** 秒 → m:ss。 */
private fun formatDuration(seconds: Int?): String {
    if (seconds == null || seconds <= 0) return "—"
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
