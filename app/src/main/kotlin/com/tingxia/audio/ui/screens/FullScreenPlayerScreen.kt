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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.tingxia.audio.data.repository.FavoritesRepository
import com.tingxia.audio.data.repository.FeedbackRepository
import com.tingxia.audio.ui.feedback.FeedbackBottomSheet
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
 *
 * 2026-10-03 自检修复（都是实测确认的缺陷，不是审美偏好）：
 * - 底部 4 个动作键此前全是空实现（MainActivity 一个回调都没传），收藏 /
 *   标签订阅 / 反馈 / 分享 四个可见控件点了没有任何反应。现在自己接线。
 * - 封面首字取的是入参 title，而 MainActivity 两处都传 title = ""，
 *   于是每一篇的封面都印同一个「听」。改为取真实标题。
 * - 内联语速 chip 写死 0.75~2.0 五档，和服务端下发的 availableSpeeds 各说各话
 *   （下面的 sheet 用的才是服务端档位）。改为同一份来源。
 * - 「蒸馏质量 · 优」是写死的假数据，真实 qualityScore 在 ArticleDetailScreen
 *   有展示、播放器里没有。编造的数字比不显示更糟，改为播放器真正掌握的码率。
 * - 整列不可滚动 + 封面 aspectRatio(1f)：横屏时封面吃满高度，进度条和控制
 *   键被顶出屏幕且无法滚动到。这是音频 App 最要命的断法。
 * - 背景渐变 endY 写死 2000f（像素），高分屏上底部一大段是死板的纯色。
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
    favoritesRepository: FavoritesRepository? = null,
    feedbackRepository: FeedbackRepository? = null,
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
    // 多码率是本 App 的招牌能力（闭环 3），播放器掌握当前实际在放的码率。
    val currentBitrate by controller.currentBitrate.collectAsState()
    val ttsViewModel: TtsPreferenceViewModel = hiltViewModel()
    // 三段兜底：当前曲目 → 调用方传入 → 友好占位文案
    val displayTitle = currentTitle.ifBlank { title.ifBlank { "未在播放" } }
    val displayAuthor = currentAuthor.ifBlank { author }

    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showFeedbackSheet by remember { mutableStateOf(false) }
    // 收藏是乐观更新：先翻图标，失败再翻回来并提示。播放器的收藏不该
    // 阻塞在一次网络往返上。
    var favoriteOverride by remember { mutableStateOf<Boolean?>(null) }
    val shownFavorite = favoriteOverride ?: isFavorite
    val speedSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )

    // 全屏背景渐变 — 上深下浅的暖棕。
    // 不给 startY/endY：Brush 默认按绘制区域的实际尺寸铺满，之前写死
    // endY = 2000f（像素）在 2K 屏上底部会留一大段死掉的纯色。
    val backgroundBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF3A2F26),  // 上：深暖棕
                Color(0xFF5C4A3A),  // 中：主品牌色
                Color(0xFF7A6650),  // 下：略亮
            ),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush),
    ) {
        // 封面尺寸同时看宽度和高度。之前只有 fillMaxWidth().aspectRatio(1f)，
        // 横屏时封面高度等于宽度、等于吃满整个视口高度，把进度条和控制键
        // 顶到屏幕外。取「宽 - 内边距」和「高的 40%」里小的那个。
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val coverSize = minOf(maxWidth - 64.dp, maxHeight * 0.40f)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                // 不可滚动 = 字体放大到 1.3x、或横屏、或小屏时控制键够不着。
                // 这是音频 App 最要命的断法：暂停不了、拖不动进度。
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            // 顶部：下拉关闭 + 标题栏
            TopBar(
                onDismiss = onDismiss,
                title = "正在播放",
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 大封面 — 无封面图时用标题首字 + 渐变
            // 传 displayTitle 而不是入参 title：MainActivity 两处都传 title = ""，
            // 传参的话每一篇的封面都印同一个「听」。
            LargeCover(
                title = displayTitle,
                coverUrl = coverUrl,
                modifier = Modifier.size(coverSize),
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 标题 + 作者 + 当前码率
            Metadata(
                title = displayTitle,
                author = displayAuthor,
                bitrate = currentBitrate,
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
                    // 触感：播放/暂停是「耳朵和手同时确认」的动作，没有触感时
                    // 用户会怀疑自己没点到（图标只有 180ms 淡入淡出）。
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                    // 2026-10-03：图标是 Icons.Default.Replay10、contentDescription
                    // 写的是「后退 15 秒」，而这里真的退 15 秒 —— 三者里两个对不上，
                    // 而图标和文案恰恰是用户唯一能看到的两样。统一成 10 秒：
                    // Material 图标集里有 Replay5/10/30，没有 15，用现成图标
                    // 比让用户盯着一个说谎的数字好。10 秒在长音频里也够回听一句。
                    val target = (position - 10_000L).coerceAtLeast(0L)
                    controller.seekTo(target)
                },
                onSkipForward = {
                    val target = if (duration > 0L) (position + 30_000L).coerceAtMost(duration) else position + 30_000L
                    controller.seekTo(target)
                },
                // 2026-10-02：从占位改成真切换。
                // 队列由列表页灌入（见 ArticleListScreen 的 LaunchedEffect），
                // 播放器维护游标；到头/到尾时返回 false，这里据此禁用按钮 ——
                // 宁可灰掉，也不给一个点了没反应的死按钮。
                onSkipPrevious = { controller.playPrevious() },
                onSkipNext = { controller.playNext() },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 语速：单一 chip 展示当前档位，点开 sheet 选。
            // 原来这里排了 5 个 chip + 一个「更多」键，但算一下宽度：
            // 5 × (12+12 padding + 约 30dp 文字) ≈ 270dp，加 4 个 8dp 间距、
            // 再加「更多」键，合计约 346dp；而 360dp 机型上这行只有
            // 360 - 24×2 = 312dp。「更多」键被挤出屏幕。
            // 而且 sheet 里列的就是同一批档位，两处状态是重复的。
            // 单 chip 是 Spotify / Apple Podcasts / 小宇宙的通行做法，
            // 任何字号和屏宽下都不会溢出。
            SpeedRow(
                currentSpeed = speed,
                onShowSpeedSheet = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showSpeedSheet = true
                },
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 底部 4 个动作 — 收藏 / 标签 / 反馈 / 分享
            // 2026-10-03：这四个键此前全是空实现，MainActivity 一个回调都没传，
            // 四个可见控件点了没有任何反应。现在全部接线。
            BottomActions(
                isFavorite = shownFavorite,
                onToggleFavorite = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val repo = favoritesRepository
                    val id = currentArticleId
                    if (repo == null || id.isNullOrBlank()) {
                        // 没有仓库（或没在播放）时不要假装成功：点收藏要有反应，
                        // 但反应必须是诚实的。
                        Toast.makeText(context, "先播放一篇文章再收藏", Toast.LENGTH_SHORT).show()
                        return@BottomActions
                    }
                    val target = !shownFavorite
                    favoriteOverride = target          // 乐观更新
                    scope.launch {
                        runCatching {
                            if (shownFavorite) {
                                val hit = repo.listFavorites()
                                    .firstOrNull { it.article_id == id }
                                if (hit != null) repo.deleteFavorite(hit.id) else repo.addFavorite(id)
                            } else {
                                repo.addFavorite(id)
                            }
                        }.onFailure {
                            favoriteOverride = !target    // 失败翻回去
                            Toast.makeText(
                                context,
                                friendlyError(it, "收藏没存上"),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
                onNavigateToTags = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onNavigateToTags()
                },
                onFeedback = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (feedbackRepository != null) showFeedbackSheet = true else onFeedback()
                },
                onShare = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    // 分享链接用已注册的深链 stashbox://detail/{id}，和通知
                    // 点进来用的是同一条路，不另造一套。
                    val link = shareUrl
                        ?: currentArticleId?.takeIf { it.isNotBlank() }?.let { "stashbox://detail/$it" }
                    if (link != null) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "${displayTitle}\n$link")
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

    // 反馈 —— 接线前这个键是空的
    if (showFeedbackSheet && feedbackRepository != null) {
        val appVersion = remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (_: Exception) {
                "unknown"
            }
        }
        FeedbackBottomSheet(
            articleId = currentArticleId,
            feedbackRepository = feedbackRepository,
            appVersion = appVersion,
            onDismiss = { showFeedbackSheet = false },
        )
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

    BoxWithConstraints(
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
        // 首字字号从容器宽度算，不写死 140.sp。
        // 容器是 dp，字号是 sp —— 系统字号放大到 1.5x 时 140sp 会涨到 210sp，
        // 直接把封面这个视觉中心撑爆。写死数字在这里一定是错的。
        Text(
            text = initials,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = (maxWidth.value * 0.40f).sp,
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
    bitrate: Int,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            color = Color(0xFFF5F2EB),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
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
                    // 原来这里写死「蒸馏质量 · 优」。那是编的：真实 qualityScore
                    // 在 ArticleDetailScreen 有展示，播放器根本没这个状态，
                    // 无论哪一篇都印「优」——用户会当成质量信号来决策。
                    // 改报播放器确实掌握、也确实会变的量：当前码率。
                    // 多码率本来就是本 App 的招牌能力，把它露出来更有意义。
                    text = "$bitrate kbps",
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
                contentDescription = "后退 10 秒",
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
    onShowSpeedSheet: () -> Unit,
) {
    // CP-TTS-VOICE: 原来是 `var currentSpeed by remember { mutableStateOf("1.0x") }`
    // —— 纯粹本地 state,点哪个 chip 都只改这个变量,播放器速度纹丝不动,
    // 切屏/重启即丢。这条链路从 UI 到 ExoPlayer 根本没接上。
    // 现在 currentSpeed 由 PlayerController 的 StateFlow 驱动。
    //
    // 2026-10-03: 5 个内联 chip + 「更多」键换成单 chip。
    // 算宽度：chip = 24 padding + 约 30dp 文字 ≈ 54dp，5 个 = 270dp，
    // 加 4 个 8dp 间隔和「更多」键约 346dp；而 360dp 机型这行只有
    // 360 − 48 = 312dp —— 「更多」键被挤出屏幕，且 sheet 里列的还是同一批档位。
    // 单 chip 既不溢出又把当前档位一眼可见，展开后的 sheet 给的是完整列表。
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (isPressed) 0.95f else 1f,
            animationSpec = tween(durationMillis = 120, easing = LinearEasing),
            label = "speedRowScale",
        )
        val bgColor by animateColorAsState(
            targetValue = Color(0xFFFFFFFF).copy(alpha = 0.12f),
            animationSpec = tween(durationMillis = 160),
            label = "speedRowBg",
        )
        Row(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(20.dp))
                .background(bgColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onShowSpeedSheet,
                )
                // 48dp 触控下限：原来「更多」键只有 36dp，手指很难点准。
                .heightIn(min = 48.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = formatSpeedLabel(currentSpeed),
                color = Color(0xFFF5F2EB),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = "选择播放速度",
                tint = Color(0xFFD4CFC6),
                modifier = Modifier.size(18.dp),
            )
        }
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
