package com.tingxia.audio.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.compose.ui.unit.sp
import com.tingxia.audio.audio.PlaybackState
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.audio.PlayerControllerEntryPoint
import com.tingxia.audio.ui.theme.WarmOchre
import androidx.hilt.navigation.compose.hiltViewModel
import android.widget.Toast
import com.tingxia.audio.ui.friendlyError
import com.tingxia.audio.ui.tts.TtsPreferenceViewModel
import com.tingxia.audio.ui.tts.formatSpeedLabel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

/**
 * CP8.5 — 全屏播放器
 *
 * 设计判断（impeccable）：
 * - Mode = Operate：用户来这里就是控制音频，所有元素服务于"听到内容"
 * - 大封面 = 主舞台；控制按钮 = 副舞台；metadata = 配角
 *
 * 动画选择（apple-design + emil）：
 * - 按钮按下：scale 0.95 + 透明度 0.7（emil: 触感反馈 160ms ease-out）
 * - 速度切换：ModalBottomSheet spring（emil: bounce 只用于 momentum）
 * - 状态切换（play ↔ pause）：Crossfade 180ms（icon swap）
 *
 * 视觉系统：
 * - 大封面渐变背景 = 品牌暖棕（WarmOchre 暗化）
 * - 进度条颜色 = Cream on dark
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenPlayerScreen(
    title: String,
    author: String? = null,
    coverUrl: String? = null,
    playerController: PlayerController? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onNavigateToTags: () -> Unit = {},
    onFeedback: () -> Unit = {},
    onShare: () -> Unit = {},
    shareUrl: String? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val controller = playerController ?: remember {
        EntryPointAccessors
            .fromApplication(context.applicationContext, PlayerControllerEntryPoint::class.java)
            .playerController()
    }

    val playbackState by controller.state.collectAsState()
    val position by controller.position.collectAsState()
    val duration by controller.duration.collectAsState()
    // P1-2：真实曲目元数据来自 PlayerController（而非调用方写死的占位标题）
    val currentTitle by controller.currentTitle.collectAsState()
    val currentAuthor by controller.currentAuthor.collectAsState()
    val currentAudioUrl by controller.currentAudioUrl.collectAsState()
    val currentArticleId by controller.currentArticleId.collectAsState()
    // CP-TTS-VOICE: 语速的真实来源。档位由服务端下发，不再在 UI 里硬编码五档。
    val speed by controller.speed.collectAsState()
    val availableSpeeds by controller.availableSpeeds.collectAsState()
    val ttsViewModel: TtsPreferenceViewModel = hiltViewModel()
    // 三段兜底：当前曲目 → 调用方传入 → 友好占位文案
    val displayTitle = currentTitle.ifBlank { title.ifBlank { "未在播放" } }
    val displayAuthor = currentAuthor.ifBlank { author }

    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    val speedSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    val scope = rememberCoroutineScope()

    // 全屏背景渐变 — 上深下浅的暖棕
    val backgroundBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF3A2F26),  // 上：深暖棕
                Color(0xFF5C4A3A),  // 中：主品牌色
                Color(0xFF7A6650),  // 下：略亮
            ),
            startY = 0f,
            endY = 2000f,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp),
        ) {
            // 顶部：下拉关闭 + 标题栏
            TopBar(
                onDismiss = onDismiss,
                title = "正在播放",
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 大封面 — 无封面时用渐变 + 大首字
            LargeCover(
                title = title,
                coverUrl = coverUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(horizontal = 32.dp),
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 标题 + 作者 + 质量分
            Metadata(
                title = displayTitle,
                author = displayAuthor,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 进度条（可拖拽 seek）
            ProgressSection(
                position = position,
                duration = duration,
                isDragging = isDragging,
                dragValue = dragValue,
                onDragStart = {
                    isDragging = true
                    dragValue = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                },
                onDragChange = { v -> dragValue = v },
                onDragFinish = { v ->
                    isDragging = false
                    if (duration > 0L) {
                        controller.seekTo((v * duration).toLong())
                    }
                },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 5 个控制按钮 — 上一首 / 后退 15s / 播放 / 前进 30s / 下一首
            ControlButtons(
                isPlaying = playbackState == PlaybackState.PLAYING,
                onPlayPause = {
                    when (playbackState) {
                        PlaybackState.PLAYING -> controller.pause()
                        // P1-2：暂停态 → resume（沿用已加载的 MediaItem，不从头播放）
                        PlaybackState.PAUSED -> controller.resume()
                        else -> {
                            // IDLE / STOPPED：仅当控制器已有真实音频源时再 play，
                            // 避免以空 URL 重建 MediaItem 导致播放失败。
                            val url = currentAudioUrl.ifBlank { "" }
                            if (url.isNotEmpty()) {
                                // 这里是重建已加载的 MediaItem（IDLE/STOPPED 恢复路径），
                                // 文章没变 → 把 currentArticleId 原样回传，
                                // 否则完听判定会认为这次播放不属于任何文章。
                                controller.play(
                                    url,
                                    displayTitle,
                                    displayAuthor,
                                    coverUrl,
                                    articleId = currentArticleId,
                                )
                            }
                        }
                    }
                },
                onSkipBackward = {
                    val target = (position - 15_000L).coerceAtLeast(0L)
                    controller.seekTo(target)
                },
                onSkipForward = {
                    val target = if (duration > 0L) (position + 30_000L).coerceAtMost(duration) else position + 30_000L
                    controller.seekTo(target)
                },
                onSkipPrevious = { /* 占位：未来接播放列表 */ },
                onSkipNext = { /* 占位：未来接播放列表 */ },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 速度切换行
            SpeedRow(
                currentSpeed = speed,
                onSpeedSelected = { value ->
                    // 与 sheet 走同一套逻辑：先改播放器(立即生效)再同步云端
                    controller.setSpeed(value)
                    scope.launch {
                        runCatching { ttsViewModel.persistSpeed(value) }
                            .onFailure {
                                // 注意：以前这里写成 .onFailure { friendlyError(...) }，
                                // 文案算出来就被丢掉 = 静默吞掉失败。必须真的提示。
                                Toast.makeText(
                                    context,
                                    friendlyError(it, "语速已生效，但同步到云端失败"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                    }
                },
                onShowSpeedSheet = { showSpeedSheet = true },
            )

            Spacer(modifier = Modifier.weight(1f))

            // 底部 4 IconButton — 收藏 / 标签 / 反馈 / 分享
            BottomActions(
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                onNavigateToTags = onNavigateToTags,
                onFeedback = onFeedback,
                onShare = {
                    if (shareUrl != null) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "听听这个: $title $shareUrl")
                        }
                        context.startActivity(Intent.createChooser(intent, "分享到"))
                    } else {
                        onShare()
                    }
                },
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showSpeedSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSpeedSheet = false },
            sheetState = speedSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            // CP-TTS-VOICE: 接真播放器 + 服务端下发的档位。
            // 此前这里是 onSpeedSelected = { hide sheet } —— 点了什么都不发生,
            // 语速只是 FullScreenPlayerScreen 内一个 remember 的本地值,
            // 播放器速度从未被改过(假闭环)。
            SpeedSheetContent(
                currentSpeed = speed,
                availableSpeeds = availableSpeeds,
                onSpeedSelected = { value ->
                    // 1) 立刻改播放器(零延迟,用户马上听到变化)
                    controller.setSpeed(value)
                    // 2) 同步到服务端(多端一致);失败只提示不回滚本地 ——
                    //    播放速度已经生效了,不该因为存不上去而把体验退回去
                    scope.launch {
                        runCatching { ttsViewModel.persistSpeed(value) }
                            .onFailure {
                                // 注意：以前这里写成 .onFailure { friendlyError(...) }，
                                // 文案算出来就被丢掉 = 静默吞掉失败。必须真的提示。
                                Toast.makeText(
                                    context,
                                    friendlyError(it, "语速已生效，但同步到云端失败"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                    }
                    scope.launch {
                        speedSheetState.hide()
                        showSpeedSheet = false
                    }
                },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────
// 顶部栏
// ─────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    onDismiss: () -> Unit,
    title: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressableIconButton(
            onClick = onDismiss,
            sizeDp = 44,
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "收起",
                tint = Color(0xFFF5F2EB),
                iconSizeDp = 28,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            color = Color(0xFFE8E4DD),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        // 右侧留白对齐
        Spacer(modifier = Modifier.width(44.dp))
    }
}

// ─────────────────────────────────────────────────────────
// 大封面
// ─────────────────────────────────────────────────────────

@Composable
private fun LargeCover(
    title: String,
    coverUrl: String?,
    modifier: Modifier = Modifier,
) {
    // 无封面图 → 用标题首字 + 渐变
    val initials = remember(title) {
        title.trim().firstOrNull()?.toString() ?: "听"
    }
    val coverBrush = remember {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF7A6650),
                Color(0xFFA67B5B),  // WarmOchre
            ),
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(coverBrush)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(24.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 140.sp,
            fontWeight = FontWeight.Light,
            fontFamily = FontFamily.Serif,
        )
        if (coverUrl == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.15f),
                                Color.Transparent,
                            ),
                            radius = 600f,
                        ),
                    ),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────
// 标题 + 作者 + 质量分
// ─────────────────────────────────────────────────────────

@Composable
private fun Metadata(
    title: String,
    author: String?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            color = Color(0xFFF5F2EB),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 28.sp,
            fontFamily = FontFamily.Serif,
        )
        if (!author.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = author,
                    color = Color(0xFFD4CFC6),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .background(
                            Color(0xFFD4CFC6).copy(alpha = 0.5f),
                            CircleShape,
                        ),
                )
                Text(
                    text = "蒸馏质量 · 优",
                    color = Color(0xFFD4CFC6),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────
// 进度条 + 时间
// ─────────────────────────────────────────────────────────

@Composable
private fun ProgressSection(
    position: Long,
    duration: Long,
    isDragging: Boolean,
    dragValue: Float,
    onDragStart: () -> Unit,
    onDragChange: (Float) -> Unit,
    onDragFinish: (Float) -> Unit,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = if (isDragging) dragValue else if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
        animationSpec = if (isDragging) {
            tween(durationMillis = 0)
        } else {
            tween(durationMillis = 250, easing = LinearEasing)
        },
        label = "progress",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = animatedProgress,
            onValueChange = { onDragChange(it) },
            onValueChangeFinished = { onDragFinish(dragValue) },
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFF5F2EB),
                activeTrackColor = Color(0xFFF5F2EB),
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val displayPosition = if (isDragging) {
                (dragValue * duration).toLong()
            } else {
                position
            }
            Text(
                text = formatTime(displayPosition),
                color = Color(0xFFD4CFC6),
                fontSize = 12.sp,
            )
            Text(
                text = formatTime(duration),
                color = Color(0xFFD4CFC6),
                fontSize = 12.sp,
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

// ─────────────────────────────────────────────────────────
// 5 控制按钮
// ─────────────────────────────────────────────────────────

@Composable
private fun ControlButtons(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressableIconButton(
            onClick = onSkipPrevious,
            sizeDp = 48,
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "上一首",
                tint = Color(0xFFE8E4DD),
                iconSizeDp = 36,
            )
        }
        PressableIconButton(
            onClick = onSkipBackward,
            sizeDp = 56,
        ) {
            Icon(
                imageVector = Icons.Default.Replay10,
                contentDescription = "后退 15 秒",
                tint = Color(0xFFE8E4DD),
                iconSizeDp = 40,
            )
        }
        // 中心播放/暂停 — 最大的按钮
        PlayPauseButton(
            isPlaying = isPlaying,
            onClick = onPlayPause,
        )
        PressableIconButton(
            onClick = onSkipForward,
            sizeDp = 56,
        ) {
            Icon(
                imageVector = Icons.Default.Forward30,
                contentDescription = "前进 30 秒",
                tint = Color(0xFFE8E4DD),
                iconSizeDp = 40,
            )
        }
        PressableIconButton(
            onClick = onSkipNext,
            sizeDp = 48,
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "下一首",
                tint = Color(0xFFE8E4DD),
                iconSizeDp = 36,
            )
        }
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 160, easing = LinearEasing),
        label = "ppScale",
    )
    Box(
        modifier = Modifier
            .size(72.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(Color(0xFFF5F2EB))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                (fadeIn(animationSpec = tween(180)) togetherWith
                    fadeOut(animationSpec = tween(180)))
            },
            label = "playPauseIcon",
        ) { playing ->
            Icon(
                imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "暂停" else "播放",
                tint = Color(0xFF3A2F26),
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────
// 速度切换行
// ─────────────────────────────────────────────────────────

@Composable
private fun SpeedRow(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onShowSpeedSheet: () -> Unit,
) {
    // CP-TTS-VOICE: 原来是 `var currentSpeed by remember { mutableStateOf("1.0x") }`
    // —— 纯粹本地 state,点哪个 chip 都只改这个变量,播放器速度纹丝不动,
    // 切屏/重启即丢。这条链路从 UI 到 ExoPlayer 根本没接上。
    // 现在 currentSpeed 由 PlayerController 的 StateFlow 驱动,点击直接落到播放器。
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        speeds.forEachIndexed { index, value ->
            if (index > 0) Spacer(modifier = Modifier.width(8.dp))
            SpeedChip(
                label = formatSpeedLabel(value),
                selected = kotlin.math.abs(value - currentSpeed) < 0.01f,
                onClick = { onSpeedSelected(value) },
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        PressableIconButton(
            onClick = onShowSpeedSheet,
            sizeDp = 36,
        ) {
            Icon(
                imageVector = Icons.Default.MoreHoriz,
                contentDescription = "更多速度",
                tint = Color(0xFFD4CFC6),
                iconSizeDp = 20,
            )
        }
    }
}

@Composable
private fun SpeedChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "chipScale",
    )
    val bgColor by animateColorAsState(
        targetValue = if (selected) Color(0xFFF5F2EB) else Color.Transparent,
        animationSpec = tween(durationMillis = 160),
        label = "chipBg",
    )
    val textColor = if (selected) Color(0xFF3A2F26) else Color(0xFFD4CFC6)

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else Color.White.copy(alpha = 0.25f),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ─────────────────────────────────────────────────────────
// 底部 4 IconButton
// ─────────────────────────────────────────────────────────

@Composable
private fun BottomActions(
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onNavigateToTags: () -> Unit,
    onFeedback: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressableIconButton(
            onClick = onToggleFavorite,
            sizeDp = 48,
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = if (isFavorite) "已收藏" else "收藏",
                tint = if (isFavorite) WarmOchre else Color(0xFFD4CFC6),
                iconSizeDp = 24,
            )
        }
        PressableIconButton(
            onClick = onNavigateToTags,
            sizeDp = 48,
        ) {
            Icon(
                imageVector = Icons.Default.Tag,
                contentDescription = "标签订阅",
                tint = Color(0xFFD4CFC6),
                iconSizeDp = 24,
            )
        }
        PressableIconButton(
            onClick = onFeedback,
            sizeDp = 48,
        ) {
            Icon(
                imageVector = Icons.Default.Feedback,
                contentDescription = "反馈",
                tint = Color(0xFFD4CFC6),
                iconSizeDp = 24,
            )
        }
        PressableIconButton(
            onClick = onShare,
            sizeDp = 48,
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "分享",
                tint = Color(0xFFD4CFC6),
                iconSizeDp = 24,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────
// 速度切换底部 sheet 内容
// ─────────────────────────────────────────────────────────

@Composable
private fun SpeedSheetContent(
    currentSpeed: Float,
    availableSpeeds: List<Float>,
    onSpeedSelected: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Text(
            text = "播放速度",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            // 变速在播放端完成,不改已生成的音频 —— 调速**不会**重跑蒸馏
            // (实测单篇约 23 分钟,让用户等一刻钟换语速不可接受)。
            // 代价说清楚: 变速后音调会跟着变。
            text = "立即生效，不影响已生成的音频；变速后音调会随之变化",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        // 档位由服务端下发,不再硬编码 —— 后台改了就改了全端行为
        val speeds = availableSpeeds.ifEmpty { PlayerController.DEFAULT_AVAILABLE_SPEEDS }
        speeds.forEach { speed ->
            SpeedSheetRow(
                label = formatSpeedLabel(speed),
                selected = kotlin.math.abs(speed - currentSpeed) < 0.01f,
                onClick = { onSpeedSelected(speed) },
            )
        }
    }
}

@Composable
private fun SpeedSheetRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            // 选中行加重 + 用主色,用户能看出「现在是这个速度」
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Speed,
                contentDescription = "当前速度",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────
// 触感按钮 — scale 0.95 + 透明度 0.7（emil-design-eng: 160ms）
// ─────────────────────────────────────────────────────────

@Composable
private fun PressableIconButton(
    onClick: () -> Unit,
    sizeDp: Int,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 160, easing = LinearEasing),
        label = "pressScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (isPressed) 0.7f else 1f,
        animationSpec = tween(durationMillis = 160, easing = LinearEasing),
        label = "pressAlpha",
    )
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// Icon 助手 — 统一处理 sizeDp
@Composable
private fun Icon(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    tint: Color,
    iconSizeDp: Int,
) {
    androidx.compose.material3.Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier.size(iconSizeDp.dp),
    )
}
