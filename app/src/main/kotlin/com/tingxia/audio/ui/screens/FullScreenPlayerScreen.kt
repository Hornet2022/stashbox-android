package com.tingxia.audio.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.tingxia.audio.ui.update.NotificationPermissionAction
import com.tingxia.audio.ui.update.rememberNotificationPermissionState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingxia.audio.audio.PlaybackState
import com.tingxia.audio.audio.SubtitleCue
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.audio.PlayerControllerEntryPoint
import com.tingxia.audio.data.repository.FavoritesRepository
import com.tingxia.audio.data.repository.FeedbackRepository
import com.tingxia.audio.ui.feedback.FeedbackBottomSheet
import com.tingxia.audio.ui.components.MusicNoteSliderThumb
import com.tingxia.audio.ui.components.MusicNoteSliderTrack
import com.tingxia.audio.ui.components.ObservePlayerPositionUpdates
import com.tingxia.audio.ui.theme.WarmOchre
import com.tingxia.audio.ui.theme.Cream
import com.tingxia.audio.ui.theme.MusicNoteKnob
import com.tingxia.audio.ui.theme.Neutral100
import com.tingxia.audio.ui.theme.Neutral200
import com.tingxia.audio.ui.theme.PlayerBgMid
import com.tingxia.audio.ui.theme.PlayerBgTop
import com.tingxia.audio.ui.theme.PlayerErrorBg
import com.tingxia.audio.ui.theme.PlayerErrorBorder
import com.tingxia.audio.ui.theme.PlayerErrorText
import com.tingxia.audio.ui.theme.PlayerHighlight
import com.tingxia.audio.ui.theme.PlayerMuted
import com.tingxia.audio.ui.theme.PlayerSheetBg
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
    // 通知权限状态（2026-10-26）：后台播放时通知栏那条控制条能不能用，
    // 全 App 只有这一份判据（见 ui/update/NotificationPermissionPolicy.kt）。
    val notificationPermission = rememberNotificationPermissionState()
    val controller = playerController ?: remember {
        EntryPointAccessors
            .fromApplication(context.applicationContext, PlayerControllerEntryPoint::class.java)
            .playerController()
    }

    // 本页有一条活的进度条 + 大量进度相关状态：可见时把轮询提到 500ms，
    // 退到后台时降频。声音 / MediaSession / 锁屏控制不经过这条路。
    ObservePlayerPositionUpdates(controller)

    val playbackState by controller.state.collectAsStateWithLifecycle()
    // 播放失败的用户可读原因（2026-10-23 新增）
    val playbackError by controller.playbackError.collectAsStateWithLifecycle()
    val position by controller.position.collectAsStateWithLifecycle()
    val duration by controller.duration.collectAsStateWithLifecycle()
    // P1-2：真实曲目元数据来自 PlayerController（而非调用方写死的占位标题）
    val currentTitle by controller.currentTitle.collectAsStateWithLifecycle()
    val currentAuthor by controller.currentAuthor.collectAsStateWithLifecycle()
    val currentAudioUrl by controller.currentAudioUrl.collectAsStateWithLifecycle()
    val currentArticleId by controller.currentArticleId.collectAsStateWithLifecycle()
    // CP-TTS-VOICE: 语速的真实来源。档位由服务端下发，不再在 UI 里硬编码五档。
    val speed by controller.speed.collectAsStateWithLifecycle()
    // 睡眠定时器剩余分钟数（0 = 未设置）。播放器层持有 —— 它必须活得比界面久。
    val sleepTimerMinutes by controller.sleepTimerMinutes.collectAsStateWithLifecycle()
    // 字幕（2026-10-23）：当前该显示的那一句。
    val currentSubtitle by controller.currentSubtitle.collectAsStateWithLifecycle()
    // 滚动字幕区（2026-10-27）：整条字幕序列 + 当前句下标。
    // 两者都由**播放位置**驱动（见 PlayerController.updateSubtitle）⇒ 与音频同频。
    val subtitleCues by controller.subtitleCues.collectAsStateWithLifecycle()
    val currentSubtitleIndex by controller.currentSubtitleIndex.collectAsStateWithLifecycle()
    // 队列边界（2026-10-23）：到头/到尾时把对应的按钮灰掉。
    //
    // 用户的原话是「上一首/下一首要等队列里真有下一项才亮」。
    // 系统媒体控件（通知栏/锁屏/耳机键）本来就由 Media3 按
    // `player.getAvailableCommands()` 自动处理；**App 内这两颗按钮**
    // 之前是永远亮的 —— 到末尾点下去毫无反应（死按钮）。
    val hasNextItem by controller.hasNextItem.collectAsStateWithLifecycle()
    val hasPreviousItem by controller.hasPreviousItem.collectAsStateWithLifecycle()
    // 播放列表控件（2026-10-27）：当前真正在播的队列快照 + 当前项下标。
    val queueItems by controller.queueItems.collectAsStateWithLifecycle()
    val currentQueueIndex by controller.currentQueueIndex.collectAsStateWithLifecycle()
    val availableSpeeds by controller.availableSpeeds.collectAsStateWithLifecycle()
    // 多码率是本 App 的招牌能力（闭环 3），播放器掌握当前实际在放的码率。
    val currentBitrate by controller.currentBitrate.collectAsStateWithLifecycle()
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
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    // 播放列表控件（2026-10-27 新增）：全屏播放器里的「队列」sheet，
    // 展示当前真正在播的队列（播放列表 + 当前文章），点某一项直接切过去。
    var showPlaylistSheet by remember { mutableStateOf(false) }
    // 收藏是乐观更新：先翻图标，失败再翻回来并提示。播放器的收藏不该
    // 阻塞在一次网络往返上。
    var favoriteOverride by remember { mutableStateOf<Boolean?>(null) }
    // 收藏态的真实来源（2026-10-27）。
    //
    // 此前 `isFavorite` 入参在 MainActivity 里**从来没传过**（默认值 false），
    // 屏内也没有任何回填 ⇒ 收藏图标永远显示"未收藏"，
    // 即便这一篇早就被收藏了。用户点一下才发现"原来早就收藏了"，
    // 于是再点一次 —— 反而把它取消收藏了。图标在**说谎**。
    //
    // 现在进屏时从仓库读一次真实状态；读失败保持"未收藏"（不猜），
    // 但绝不因为读失败就把状态锁死。
    var favoriteFromRepo by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(currentArticleId, favoritesRepository) {
        val repo = favoritesRepository
        val id = currentArticleId
        favoriteFromRepo = null
        if (repo == null || id.isNullOrBlank()) return@LaunchedEffect
        favoriteFromRepo = runCatching {
            repo.listFavorites().any { it.article_id == id }
        }.getOrNull()
    }
    val shownFavorite = favoriteOverride ?: favoriteFromRepo ?: isFavorite
    val speedSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )

    // 全屏背景渐变 — 上深下浅的暖棕。
    // 不给 startY/endY：Brush 默认按绘制区域的实际尺寸铺满，之前写死
    // endY = 2000f（像素）在 2K 屏上底部会留一大段死掉的纯色。
    val backgroundBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                PlayerBgTop,  // 上：深暖棕
                PlayerBgMid,  // 中：主品牌色
                PlayerMuted,  // 下：略亮
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
        // 2026-10-03 真机发现：windowInsetsPadding 原来挂在**可滚动 Column 内部**，
        // 于是上下 inset 变成了「滚动内容的一部分」—— 滚到顶时那段 padding 在
        // 屏幕之外，播放/暂停按钮就压到了导航栏上被切掉一截。
        // inset 必须加在滚动视口的**外面**：视口本身就不含系统栏，
        // 任何滚动位置都不可能有内容跑到导航栏底下。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
        //
        // 0.40 → 0.32（2026-10-23）：原来的比例让整列**放不进视口** ——
        // 底部的「收藏/标签订阅/反馈/分享」整排落在导航栏后面（实测
        // y=3090~3200，导航栏从 ~3080 开始），用户根本点不到。
        // 缩小封面 + 收紧间距后，这一屏能完整放下，滚动只作为小屏兜底。
        // ── 布局结构重构（2026-10-27，产品设计规范 / Material 3）──────────
        //
        // 原结构 = 「一列到底 + 一串裸 Spacer + 固定比例封面 + 整列滚动」。
        // 真机截图量出来的三个结构性问题：
        // ① 封面固定 = min(宽−64dp, 高×0.32) ⇒ 常见机型上只有 274dp，
        //    上下却空着 —— 视觉重心偏上，底部一条大片留白；
        // ② 字幕区**固定**保留两行高度，无字幕时就是一条 52dp 的空带，
        //    把「元信息」和「进度条」隔成两块互不相干的区域；
        // ③ 区块之间全靠裸 Spacer(16.dp) 堆叠，没有分组 —— 语速 chip 与
        //    底部动作行的视觉重量完全一样，用户分不出「播放设置」和「内容操作」。
        //
        // 新结构（四段，分组间距不同）：
        //   顶部栏 ｜ 内容段（封面 + 元信息 + 字幕） ｜ 传输段（进度 + 播放键）
        //          ｜ 设置段（语速 / 定时） ｜ 操作段（收藏 / 标签 / 反馈 / 分享）
        //
        // 关键是**封面改成弹性**：它吃掉内容段的剩余高度，并按可用宽度取最大
        // 正方形。于是（a）常见机型上封面直接铺满内容宽度；（b）字幕出现/消失、
        // 标题 1 行↔3 行造成的高度变化**由封面吸收**，下面的控制键不会被推走 ——
        // 这正是原来要用「固定高度硬扛」的那个跳动问题。
        //
        // 矮屏（可用高 < 640dp：横屏、小屏、字体放大到 1.3x）才退回
        // 「固定封面 + 整页滚动」兜底 —— 音频 App 最要命的断法是
        // 暂停不了、拖不动进度，所以那条兜底路径必须保留。
        // 视口尺寸要在进入 Column 之前取出来：Column 里 maxWidth/maxHeight 的
        // 隐式接收者变成了 Column 自己，引用不到外层 BoxWithConstraints 的。
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        val compactHeight = viewportHeight < 640.dp
        // R3.2：compact 档的网格边距是 16dp（原来用 24dp）。
        // 不只是合规问题 —— 底部那一行（倍速/定时器/4 个动作键）要一行放下，
        // 16dp 边距多出的 16dp 正好是那行的余量。
        val horizontalPadding = 16.dp
        // ── 竖屏一列 / 横屏两栏 ────────────────────────────────────────────
        //
        // 横屏原来的结构是**错的**：封面按「宽 − padding」算尺寸，在横屏
        // 变成一个缩在左上角的小方块，右侧整片空白（真机截图 /tmp/ui5/I2.png）。
        // 竖屏的「一列到底」在横屏不成立 —— 音频 App 横屏时视线是横向的，
        // 内容应该**并排**（封面+元信息 ｜ 字幕+传输+操作），不是堆叠。
        //
        // 判定用**视口宽高**而不是屏幕方向：折叠屏展开 / 分屏 / 多窗口下，
        // 配置里的方向和实际可用尺寸不是一回事。
        val isLandscape = viewportWidth > viewportHeight

        // 面板抽成**局部 @Composable lambda**而不是顶层函数：它们要闭包捕获
        // 几十个播放器状态（position/duration/speed/…），拆成顶层函数就得写
        // 一长串参数表，而且这些状态几乎每处都要用。
        //
        // 竖屏：内容段（封面 + 元信息 + 字幕） → 传输段 → 设置段 → 操作段。
        val coverPane: @Composable (Modifier, Alignment) -> Unit = { paneModifier, paneAlignment ->
            // 大封面 — 无封面图时用标题首字 + 渐变
            // 传 displayTitle 而不是入参 title：MainActivity 两处都传 title = ""，
            // 传参的话每一篇的封面都印同一个「听」。
            Box(
                modifier = paneModifier,
                contentAlignment = paneAlignment,
            ) {
                LargeCover(
                    title = displayTitle,
                    coverUrl = coverUrl,
                    modifier = Modifier
                        .sizeIn(maxWidth = 420.dp, maxHeight = 420.dp)
                        .aspectRatio(1f),
                )
            }
        }

        val metaPane: @Composable () -> Unit = {
            // 标题 + 作者 + 当前码率
            Metadata(
                title = displayTitle,
                author = displayAuthor,
                bitrate = currentBitrate,
            )
            Spacer(modifier = Modifier.height(if (isLandscape) 12.dp else 16.dp))
        }

        val subtitlePane: @Composable () -> Unit = {
            // 滚动字幕区（2026-10-27 新增）。
            //
            // 之前这里只有**当前一句**（SubtitleArea，固定两行）。它确实是
            // 跟音频同频的（位置 → cueAt），但用户看到的是「一句一换」，
            // 没法回看前面说过什么，也没法一眼看到上下文。
            //
            // 现在是**整条序列的滚动视图**：当前句高亮（字重 + 亮度），
            // 列表随播放位置自动滚到当前句 ⇒ 滚动和高亮**同源**，
            // 因此天然与音频进度同频。
            //
            // 高度**固定**（见 [ScrollingSubtitleArea]）：句子 1 行↔2 行、
            // 当前句在列表中间还是末尾，都不引起下方任何位移 ——
            // 高度变化由弹性封面吸收。
            ScrollingSubtitleArea(
                cues = subtitleCues,
                // 播完了（位置 ≥ 时长）时，时间轴已经「过界」，下标是 -1 ——
                // 但用户此刻看到的应该是**最后一句**（那正是刚听完的），
                // 否则整页会停在稿子开头（实测：打开就已播完的文章，
                // 字幕区停在第一句且没有高亮，像坏了）。
                currentIndex = if (
                    currentSubtitleIndex < 0 &&
                    subtitleCues.isNotEmpty() &&
                    duration > 0L &&
                    position >= duration
                ) {
                    subtitleCues.lastIndex
                } else {
                    currentSubtitleIndex
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val bannersPane: @Composable () -> Unit = {
            // 播放失败提示 + 重试（2026-10-23 新增）
            //
            // 此前播放失败**完全没有反馈**：状态是乐观设成 PLAYING 的，
            // 用户只看到「按了没声音」，既不知道失败了也不知道该干什么。
            // 这里把 [com.tingxia.audio.audio.PlayerController.playbackError]
            // 显示出来，并给一个**能点**的重试入口 ——
            // 失败后状态是 ERROR，而 resume() 只认 PAUSED，
            // 所以播放键在失败态必须走 retry()，否则又是一个死按钮。
            playbackError?.let { message ->
                PlaybackErrorBanner(
                    message = message,
                    onRetry = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        controller.retry()
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 通知栏控制条不可用时的提示（2026-10-26 新增）。
            //
            // 为什么必须有：后台播放时，**通知栏那条控制条就是用户唯一的控制入口**
            // （锁屏、音量面板、耳机键都走它）。它不出现时，用户看到的是
            // 「App 正常、声音正常、但通知栏空着」—— **没有任何线索指向原因**。
            // 内测反馈原话就是「更新后通知栏没有了」，而实测证明 0.4.1/0.4.2
            // 的通知代码逐字节一致、四个场景复测都正常 ⇒ 真正卡住用户的是
            // 「权限被永久拒绝 / 在系统里关了通知」，而这两件事 App 之前
            // **既不显示状态、也给不出任何可执行的动作**。
            //
            // 这里把「为什么没有」和「现在能做什么」直接摆在播放页上。
            if (notificationPermission.action != NotificationPermissionAction.ALREADY_ON) {
                NotificationControlHintBanner(
                    text = notificationControlHintText(notificationPermission.action),
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        notificationPermission.perform()
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        val progressPane: @Composable () -> Unit = {
            // ── 传输段：进度条 ──
            // 与上面的「内容段」之间留 20dp：进度条属于「正在播这一篇」，
            // 不该和标题/字幕粘在一起（那会让进度条看起来像字幕的一部分）。
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
            Spacer(modifier = Modifier.height(if (isLandscape) 12.dp else 20.dp))
        }

        val controlPane: @Composable () -> Unit = {
            // 5 个控制按钮 — 上一首 / 后退 10s / 播放 / 前进 30s / 下一首
            ControlButtons(
                // RECOVERING 也算「在播」（2026-10-26）：断流恢复中，
                // 按钮该显示暂停、点了也该能暂停。
                isPlaying = playbackState == PlaybackState.PLAYING ||
                    playbackState == PlaybackState.RECOVERING,
                onPlayPause = {
                    // 触感：播放/暂停是「耳朵和手同时确认」的动作，没有触感时
                    // 用户会怀疑自己没点到（图标只有 180ms 淡入淡出）。
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    when (playbackState) {
                        // RECOVERING 与 PLAYING 同义：暂停会顺带取消自动重连
                        // （见 PlayerController.pause）。
                        PlaybackState.PLAYING, PlaybackState.RECOVERING -> controller.pause()
                        // P1-2：暂停态 → resume（沿用已加载的 MediaItem，不从头播放）
                        PlaybackState.PAUSED -> controller.resume()
                        // 失败态 → 重试。走 play() 重来一遍，音频地址可能已失效，
                        // 重新取一次比原地 prepare 更有机会成。
                        PlaybackState.ERROR -> controller.retry()
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
                hasPreviousItem = hasPreviousItem,
                hasNextItem = hasNextItem,
            )
        }

        // ── 底部一行：倍速 / 定时器 / 收藏 / 标签 / 反馈 / 分享 ──────────
        //
        // 用户原话：「把倍速、定时器和最底下的四个按钮放一行，两行有些丑」。
        //
        // 为什么用 [FlowRow] 而不是固定 Row：
        // 这一行有 6 个控件（2 个 chip + 4 个 48dp 图标键）。按 R6.5
        // 图标键不能小于 48dp，4 个就是 192dp；两个 chip 再加文字约 170dp ——
        // 合计约 370dp。窄机型（360dp，扣边距只剩 328dp）放不下，
        // 硬塞会**裁掉分享键**（那是最常用的一个）。
        // FlowRow 的语义正好是「**能一行就一行，放不下才换行**」：
        // 宽一点的机型（横屏、平板、大屏手机）得到用户要的一行；
        // 窄机型优雅地退回两行，且任何一个键都不会被裁掉。
        val bottomBarPane: @Composable () -> Unit = {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 语速 chip（单 chip，展开后是服务端下发的完整档位列表）
                SpeedChip(
                    currentSpeed = speed,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showSpeedSheet = true
                    },
                )
                // 睡眠定时器 chip
                SleepTimerChip(
                    minutes = sleepTimerMinutes,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showSleepTimerSheet = true
                    },
                )
                // 内容操作四键（收藏 / 标签 / 反馈 / 分享）
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
                // 离导航栏留一点呼吸：这一行是全屏页最后一行内容，
                // 贴着系统栏会显得被挤下去（实测视觉上像被切了一截）。
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
        ) {
            // 顶部：下拉关闭 + 标题栏
            TopBar(
                onDismiss = onDismiss,
                title = "正在播放",
                // 播放列表控件（2026-10-27）：右侧换成队列入口。
                // 原先是「右侧留白对齐」的 Spacer —— 现在这里放一个
                // 队列图标键，点了弹「当前队列」sheet。
                trailingAction = {
                    PressableIconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showPlaylistSheet = true
                        },
                        // 48dp：R6.5 最小触控区。原先 44dp 比 Material 规范小 4dp。
                        sizeDp = 48,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "播放列表",
                            tint = Cream,
                            iconSizeDp = 24,
                        )
                    }
                },
            )

            Spacer(modifier = Modifier.height(if (isLandscape) 8.dp else 12.dp))

            if (isLandscape) {
                // ── 横屏：两栏 ──────────────────────────────────────────────
                //
                // 左栏 = 封面 + 元信息（视觉主体）；右栏 = 字幕 + 传输 + 设置 + 操作
                // （全部控制）。两栏各自独立滚动 —— 横屏可用高度很小
                // （本机 3200×1440 旋转后约 411dp），一栏挤不下时另一栏
                // 不该跟着被推走。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    // 左栏 = **内容**：封面 + 元信息 + 内容操作（收藏/标签/反馈/分享）。
                    // 这三样作用在同一篇文章上（操作段本来就比其它段多留 24dp），
                    // 放在一起语义自洽，也刚好把左栏的空余高度用掉 ——
                    // 否则 4 个动作键只能挤在右栏底部并被导航栏切掉。
                    Column(
                        modifier = Modifier
                            .weight(0.42f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // 横屏封面按**高度**取尺寸：横屏的瓶颈是高度不是宽度，
                        // 按宽度算会得到一个高过视口的大方块（原来就是这个毛病）。
                        // 左对齐（CenterStart）而不是居中：下面的标题是左对齐的，
                        // 封面与标题的左边缘对齐才读得出「一组」。
                        // 尺寸同时受**高度**约束：横屏的瓶颈是高度
                        // （本机旋转后可用高约 387dp），按宽度算会得到一个
                        // 高过视口的大方块 —— 原来就是这个毛病。
                        // 高度再留一档余量，好让下面的标题 + 4 个动作键
                        // 一屏放得下（实测 0.58f 时底部动作键被导航栏切掉）。
                        val landscapeCoverSize = minOf(
                            viewportWidth * 0.32f,
                            viewportHeight * 0.46f,
                        ).coerceIn(120.dp, 420.dp)
                        coverPane(Modifier.size(landscapeCoverSize), Alignment.CenterStart)
                        Spacer(modifier = Modifier.height(16.dp))
                        metaPane()
                    }

                    // 右栏 = **播放**：字幕 + 进度 + 播放键 + 播放设置。
                    Column(
                        modifier = Modifier
                            .weight(0.58f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        subtitlePane()
                        bannersPane()
                        progressPane()
                        controlPane()
                        // 底部一行：倍速 / 定时器 / 收藏 / 标签 / 反馈 / 分享。
                        // 宽裕的横屏里它就是**一行**（用户要的）。
                        bottomBarPane()
                    }
                }
            } else {
                // ── 竖屏：一列 ──────────────────────────────────────────────
                //
                // 封面弹性（weight）：吃掉内容段的剩余高度，尺寸按可用宽度
                // 取**最大正方形**。于是（a）常见机型上封面直接铺满内容宽度；
                //（b）字幕出现/消失、标题 1 行↔3 行造成的高度变化**由封面吸收**，
                // 下面的控制键不会被推走 —— 这正是原来要用「固定高度硬扛」的
                // 那个跳动问题。
                //
                // 矮屏（可用高 < 640dp：小屏、字体放大到 1.3x）才退回
                // 「固定封面 + 整页滚动」兜底 —— 音频 App 最要命的断法是
                // 暂停不了、拖不动进度，所以那条兜底路径必须保留。
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (compactHeight) {
                                Modifier.verticalScroll(rememberScrollState())
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    coverPane(
                        if (compactHeight) {
                            Modifier.size(
                                minOf(viewportWidth - horizontalPadding * 2, viewportHeight * 0.30f),
                            )
                        } else {
                            Modifier.fillMaxWidth().weight(1f)
                        },
                        Alignment.Center,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    metaPane()
                    subtitlePane()
                    bannersPane()
                    progressPane()
                    controlPane()
                    bottomBarPane()
                }
            }
        }
        }
    }

    if (showSpeedSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSpeedSheet = false },
            sheetState = speedSheetState,
            // 2026-10-27：原来用 `MaterialTheme.colorScheme.surface` ——
            // 浅色主题下那是**米白**，于是浅色模式下会有一块白 sheet 盖在
            // 深色播放器上（另外两个 sheet 用的是播放器本地深色，不一致）。
            // 播放器是独立的深色界面，它的所有 sheet 都该用同一套本地底色。
            containerColor = PlayerSheetBg,
            contentColor = Cream,
            // 拖拽把手：让用户知道这块可以往下拖走（R5.9）
            dragHandle = { BottomSheetDefaults.DragHandle(color = PlayerMuted) },
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
    // 睡眠定时器 sheet（2026-10-23 新增）
    if (showSleepTimerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSleepTimerSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PlayerBgTop,
            contentColor = Cream,
            // 拖拽把手：让用户知道这块可以往下拖走（R5.9）
            dragHandle = { BottomSheetDefaults.DragHandle(color = PlayerMuted) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = "睡眠定时器",
                    color = Cream,
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "到点自动暂停，播放位置会保留，醒来还能接着听。",
                    color = Neutral200,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
                SLEEP_TIMER_OPTIONS.forEach { minutes ->
                    val label = if (minutes == 0) "关闭定时器" else "$minutes 分钟"
                    val active = sleepTimerMinutes == minutes
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (active) PlayerHighlight.copy(alpha = 0.16f)
                                else Color.Transparent
                            )
                            .clickable {
                                controller.setSleepTimerMinutes(minutes)
                                showSleepTimerSheet = false
                            }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = label,
                            color = Cream,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (active) {
                            Text(
                                text = "当前",
                                color = Neutral200,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }

    // 播放列表 sheet（2026-10-27 新增）：展示当前真正在播的队列，
    // 点某一项直接切过去。内容与播放列表同步 —— 队列 = 播放列表 + 当前文章
    // （见 PlayerController.buildQueue），且系统媒体面板/耳机键换曲也会反映在这里。
    if (showPlaylistSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPlaylistSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PlayerSheetBg,
            contentColor = Cream,
            // 拖拽把手：让用户知道这块可以往下拖走（R5.9）
            dragHandle = { BottomSheetDefaults.DragHandle(color = PlayerMuted) },
        ) {
            PlaylistSheetContent(
                items = queueItems,
                currentIndex = currentQueueIndex,
                onSelect = { index ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    controller.seekToQueueIndex(index)
                    showPlaylistSheet = false
                },
            )
        }
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
    trailingAction: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressableIconButton(
            onClick = onDismiss,
            // 48dp：R6.5 最小触控区（原先 44dp）。
            sizeDp = 48,
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "收起",
                tint = Cream,
                iconSizeDp = 28,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            color = Neutral100,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        // 右侧：有 trailingAction 就放它，否则留白对齐（保持标题居中）。
        if (trailingAction != null) {
            trailingAction()
        } else {
            Spacer(modifier = Modifier.width(48.dp))
        }
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
    //
    // 2026-10-03 真机发现：原来直接取 trim 后的第一个字符。
    // 中文标题极常以标点开头 —— 少数派那篇是「「让我告诉你，我所了解小米10PRO
    // 的一切」…」，于是整张封面印了一个孤零零的「，在 296dp 的一方块上像
    // 一个渲染坏了的括号，而不是封面。
    // 改成取**第一个字母/数字**：标点、空格、以及开引号括号一律跳过。
    // 标题全是非字符合计（纯 emoji / 符号）时才回落到「听」。
    val initials = remember(title) {
        title.trim().firstOrNull { it.isLetterOrDigit() }?.toString() ?: "听"
    }
    val coverBrush = remember {
        Brush.linearGradient(
            colors = listOf(
                PlayerMuted,
                WarmOchre,  // WarmOchre
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
        //
        // 语义：这个首字是**装饰**，不是信息（R6.2）。
        // 标题正文已经在上方单独播报了，TalkBack 再把封面读一遍
        // （"小" / "听" / 某个汉字）纯属噪音 —— 而且读出来的是个残缺的字，
        // 用户会以为标题就是这么个单字。⇒ 整个封面对无障碍树隐藏。
        Text(
            text = initials,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = (maxWidth.value * 0.40f).sp,
            fontWeight = FontWeight.Light,
            fontFamily = FontFamily.Serif,
            modifier = Modifier.clearAndSetSemantics { },
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
            color = Cream,
            // 标题走 typography role（titleLarge = 20sp Serif SemiBold / lineHeight 28sp）。
            // 原先写死 22sp + 单独指定 lineHeight 28sp + Serif —— 现在由 role 统一带，
            // 字号体系只有一处真相（R4.3）。
            style = MaterialTheme.typography.titleLarge,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (!author.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = author,
                    color = Neutral200,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .background(
                            Neutral200.copy(alpha = 0.5f),
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
                    color = Neutral200,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────
// 字幕区
// ─────────────────────────────────────────────────────────

/**
 * 滚动字幕区（2026-10-27 新增，替代原来的单句 [SubtitleArea]）。
 *
 * ## 为什么是「滚动 + 高亮」而不是「只显示当前句」
 * 只显示当前句时，用户的上下文只有一句话：听到「因此我们可以…」时，
 * 他不知道「因此」指的是什么。滚动区把**整条听感稿**摆在眼前，
 * 当前句高亮，其余压暗 —— 用户既知道现在说到哪，也能回看前面。
 *
 * ## 怎么保证「与音频进度同频」
 * 高亮与滚动都由**同一个下标**驱动，而这个下标来自播放位置
 * （`PlayerController.updateSubtitle` → `SubtitleTimeline.indexAt`），
 * 不是 UI 自己算的。所以「听到哪」和「看到哪」**不可能**对不上。
 *
 * ## 为什么高度固定
 * 句子有长有短（1 行↔2 行），如果高度跟着内容变，每换一句下方所有控件
 * 就会跳一下。高度固定 ⇒ 换句只改颜色和滚动位置，**布局零位移**。
 *
 * ## 无障碍
 * 用真正的 [LazyColumn]（不是 Canvas 自绘）：TalkBack 能逐句朗读，
 * 也能用系统滚动手势翻看（自绘的 Canvas 文字对 TalkBack 是不可见的）。
 */
@Composable
private fun ScrollingSubtitleArea(
    cues: List<SubtitleCue>,
    currentIndex: Int,
    modifier: Modifier = Modifier,
) {
    // 没有稿子 → 什么都不画（不留空带）。
    if (cues.isEmpty()) return
    val listState = rememberLazyListState()
    // 当前句变化 → 把它滚到视口中间。
    // 偏移量用「视口一半 − 半行」近似居中：句高不固定（1~2 行），
    // 精确居中反而会在长句时跳。
    LaunchedEffect(currentIndex, cues.size) {
        if (currentIndex < 0) return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize.height
        if (viewport <= 0) return@LaunchedEffect
        listState.animateScrollToItem(currentIndex, -(viewport / 2 - 28))
    }
    LazyColumn(
        state = listState,
        // 固定高度：内容再长也不推挤下方控件（见上面的「为什么高度固定」）。
        //
        // 上下各 14dp 的**渐隐**（用 DstOut 把边缘文字擦掉，不是盖一层底色）：
        // 列表滚动时上下边缘会切到半行字，那道硬边看起来像被裁坏了；
        // 渐隐让它自然消失，而且不依赖背景是什么颜色（播放页背景是渐变，
        // 盖一层实色会露出一条色带）。
        // 固定高度：内容再长也不推挤下方控件（见上面的「为什么高度固定」）。
        //
        // 边缘被切到半行字是**滚动列表的正常形态**（这里的内容本来就在滚动），
        // 不要用 DstOut 去「擦掉」它：实测两种做法都会翻车 ——
        // 直接 DstOut 把整个窗口图层打穿（字幕区变成黑框），
        // 离屏图层 + DstOut 在 LazyColumn 上整块渲染为空（字幕全没了）。
        // 需要更柔的边缘时，正确做法是给列表加上下内边距让切边落在
        // 行与行之间，而不是在绘制层做文章。
        modifier = modifier.height(ScrollingSubtitleAreaHeight),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(cues, key = { index, _ -> index }) { index, cue ->
            val isCurrent = index == currentIndex
            Text(
                text = cue.text,
                // R4.3：走 typography role，不写死字号。
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                // R6.7：当前句 Cream（对比度 15.4:1），其余 Neutral200 压到
                // 0.8（实测仍 > 4.5:1）—— 压暗是为了「一眼看到现在这句」，
                // 不是为了省对比度。
                color = if (isCurrent) Cream else Neutral200.copy(alpha = 0.8f),
                // R6.8：不靠颜色单独表意 —— 当前句同时加粗。
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

/** 滚动字幕区固定高度（约 3 行 16sp/26dp 行高）。 */
private val ScrollingSubtitleAreaHeight = 88.dp

@Composable
@OptIn(ExperimentalMaterial3Api::class) // Slider.thumb 参数是 experimental API（material3 1.3.1）
private fun ProgressSection(
    position: Long,
    duration: Long,
    isDragging: Boolean,
    dragValue: Float,
    onDragStart: () -> Unit,
    onDragChange: (Float) -> Unit,
    onDragFinish: (Float) -> Unit,
) {
    //
    // 目标进度：拖动时用拖动值，否则用真实播放位置。
    val targetProgress = if (isDragging) {
        dragValue
    } else if (duration > 0L) {
        (position.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }

    //
    // 动画时长必须**大于**位置轮询间隔（[com.tingxia.audio.audio.PlayerController]
    // 的 POLL_INTERVAL_MS = 500ms），否则滑块就是「走一段、停一段」——
    // 250ms 的 tween 配 500ms 一次的目标准确地产生这个节奏：
    // 走 250ms → 停 250ms → 再走。真机实测（小米 Mi 11，5:17 的音频，
    // 轨道宽 1264px）：每次轮询滑块只挪 1.7px，于是肉眼看到的是
    // 「一格一格地跳」，而不是连续滑动。
    //
    // 800ms > 500ms → 动画永远在飞 → 滑块连续。
    // 稳态滞后约 0.6 次轮询的量（≈1px），看不出来。
    //
    // 大幅跳变（seek / 换歌）时改用 120ms 快速跟上 ——
    // 否则拖完进度条要等 800ms 才到位，手感是「粘的」。
    //
    // 用 [Animatable] 而不是 animateFloatAsState：
    // 判据需要「当前动画值」来区分「正常推进」和「大幅跳变」，
    // 而 animateFloatAsState 拿不到自己当前的动画值（它就是返回值本身）。
    // Animatable 的 value 既是当前值也是 State → 读它就会触发重绘。
    val progress = remember { Animatable(0f) }
    LaunchedEffect(targetProgress, isDragging) {
        val spec = when {
            isDragging -> tween<Float>(durationMillis = 0)
            // 大幅跳变（seek / 换歌 / 换集）→ 120ms 快速跟上，
            // 否则拖完进度条要等 800ms 才到位，手感是「粘的」。
            kotlin.math.abs(targetProgress - progress.value) > 0.02f ->
                tween<Float>(durationMillis = 120, easing = LinearEasing)
            // 正常推进 → 800ms（> 轮询间隔 500ms），动画永远在飞 → 滑块连续。
            else -> tween<Float>(durationMillis = 800, easing = LinearEasing)
        }
        progress.animateTo(targetProgress, spec)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = progress.value,
            onValueChange = { onDragChange(it) },
            onValueChangeFinished = { onDragFinish(dragValue) },
            colors = SliderDefaults.colors(
                thumbColor = Cream,
                activeTrackColor = Cream,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
            // 音符指示器（R-MUSIC-THUMB 2026-10-27）：替换默认圆形 thumb。
            // 圆底用墨黑、字形用米白：圆底**不得**等于轨道色（Cream）——
            // 同色会把圆底和轨道糊成一块（用户 2026-10-27 反馈）。
            thumb = { MusicNoteSliderThumb(color = MusicNoteKnob, glyphColor = Cream) },
            // 自绘轨道（R-MUSIC-THUMB）：Material3 默认轨道在 thumb 两侧固定留
            // 6dp 缺口且末端直角切断 ⇒ 轨道与圆底之间出现一块「白色方块」。
            // 这里让轨道连续铺满、恰好止于圆底左缘。
            track = {
                MusicNoteSliderTrack(
                    fraction = progress.value,
                    activeColor = Cream,
                    inactiveColor = Color.White.copy(alpha = 0.25f),
                )
            },
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
                color = Neutral200,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = formatTime(duration),
                color = Neutral200,
                style = MaterialTheme.typography.labelMedium,
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

/**
 * 播放失败提示条（2026-10-23 新增）。
 *
 * 为什么单独一个组件而不是弹个 Toast：Toast 会自己消失，而「播放失败」
 * 是**用户必须做点什么**才能继续的状态（重试）。消失了就等于没提示。
 */
@Composable
private fun PlaybackErrorBanner(
    message: String,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PlayerErrorBg, RoundedCornerShape(12.dp))
            .border(1.dp, PlayerErrorBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = PlayerErrorText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(10.dp))
        // 「重试」是这条横幅里**唯一**能让用户脱离失败态的入口 ——
        // 它的触控区原来只有 ~28dp（文字高 + 上下 4dp padding），
        // 是整个屏幕上最难点中却最必须点中的控件。
        // 现在用 TextButton：自带 48dp 最小触控区、涟漪反馈和正确的语义角色。
        TextButton(
            onClick = onRetry,
            colors = ButtonDefaults.textButtonColors(
                contentColor = PlayerErrorText,
            ),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                text = "重试",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
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
    hasPreviousItem: Boolean,
    hasNextItem: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressableIconButton(
            onClick = onSkipPrevious,
            sizeDp = 48,
            enabled = hasPreviousItem,
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "上一首",
                tint = Neutral100,
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
                tint = Neutral100,
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
                tint = Neutral100,
                iconSizeDp = 40,
            )
        }
        PressableIconButton(
            onClick = onSkipNext,
            sizeDp = 48,
            enabled = hasNextItem,
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "下一首",
                tint = Neutral100,
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
            .background(Cream)
            .clickable(
                interactionSource = interactionSource,
                // R7.5：Material 涟漪。
                // 原来这里是 indication = null（涟漪被关掉了）——
                // 这个键有自绘的 scale + alpha 按压反馈，但**没有涟漪**，
                // 手指按下去看不到"按在哪儿"。
                // 涟漪和自绘动画不冲突：涟漪负责位置，自绘负责整颗键的响应。
                indication = ripple(),
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
                tint = PlayerBgTop,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────
// 速度切换行
// ─────────────────────────────────────────────────────────

@Composable
private fun SpeedChip(
    currentSpeed: Float,
    onClick: () -> Unit,
) {
    // CP-TTS-VOICE：原来是 `var currentSpeed by remember { mutableStateOf("1.0x") }`
    // —— 纯粹本地 state，点哪个 chip 都只改这个变量，播放器速度纹丝不动，
    // 切屏/重启即丢。这条链路从 UI 到 ExoPlayer 根本没接上。
    // 现在 currentSpeed 由 PlayerController 的 StateFlow 驱动。
    //
    // 2026-10-03: 5 个内联 chip + 「更多」键换成单 chip。
    // 算宽度：chip = 24 padding + 约 30dp 文字 ≈ 54dp，5 个 = 270dp，
    // 加 4 个 8dp 间隔和「更多」键约 346dp；而 360dp 机型这行只有
    // 360 − 32 = 328dp —— 「更多」键被挤出屏幕，且 sheet 里列的还是同一批档位。
    // 单 chip 既不溢出又把当前档位一眼可见，展开后的 sheet 给的是完整列表。
    //
    // 2026-10-27：水平内边距从 20dp 收到 12dp —— 底部那一行要放下
    // 「倍速 + 定时器 + 4 个 48dp 图标键」，省下的 16dp 正是那行的余量。
    // 高度仍是 48dp（R6.5 触控下限没动）。
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "speedChipScale",
    )
    val bgColor by animateColorAsState(
        targetValue = PlayerHighlight.copy(alpha = 0.12f),
        animationSpec = tween(durationMillis = 160),
        label = "speedChipBg",
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
                // R7.5：Material 涟漪（原来 indication = null）。
                indication = ripple(),
                onClick = onClick,
            )
            // 48dp 触控下限：原来「更多」键只有 36dp，手指很难点准。
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = formatSpeedLabel(currentSpeed),
            color = Cream,
            style = MaterialTheme.typography.labelLarge,
        )
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = "选择播放速度",
            tint = Neutral200,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SleepTimerChip(
    minutes: Int,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "timerChipScale",
    )
    val bgColor by animateColorAsState(
        targetValue = PlayerHighlight.copy(alpha = 0.12f),
        animationSpec = tween(durationMillis = 160),
        label = "timerChipBg",
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
                // R7.5：Material 涟漪（原来 indication = null）。
                indication = ripple(),
                onClick = onClick,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (minutes > 0) "定时 $minutes 分钟" else "定时器",
            color = Cream,
            style = MaterialTheme.typography.labelLarge,
        )
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = "设置睡眠定时器",
            tint = Neutral200,
            modifier = Modifier.size(18.dp),
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
    // 2026-10-27：它现在是「底部一行」里的一个整体（FlowRow 的一个 item）。
    // 原来 fillMaxWidth + SpaceEvenly 会占满整行 → 在一行布局里等于把
    // 前面两个 chip 挤到第二行。改成内容宽度 + 紧凑间距。
    Row(
        modifier = Modifier,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressableIconButton(
            onClick = onToggleFavorite,
            sizeDp = 48,
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = if (isFavorite) "已收藏" else "收藏",
                tint = if (isFavorite) WarmOchre else Neutral200,
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
                tint = Neutral200,
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
                tint = Neutral200,
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
                tint = Neutral200,
                iconSizeDp = 24,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────
// 播放列表 sheet 内容（2026-10-27 新增）
// ─────────────────────────────────────────────────────────

@Composable
private fun PlaylistSheetContent(
    items: List<PlayerController.QueueItem>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Text(
            text = "播放列表${if (items.isNotEmpty()) "（${items.size}）" else ""}",
            color = Cream,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "点任意一篇直接切过去；列表与你的播放列表同步。",
            color = Neutral200,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (items.isEmpty()) {
            Text(
                text = "队列是空的",
                color = Neutral200,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.articleId }) { index, item ->
                    val isCurrent = index == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isCurrent) PlayerHighlight.copy(alpha = 0.16f)
                                else Color.Transparent
                            )
                            .clickable { onSelect(index) }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isCurrent) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isCurrent) "正在播放" else "播放这一篇",
                            tint = if (isCurrent) WarmOchre else Neutral200,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title?.takeIf { it.isNotBlank() } ?: "（无标题）",
                                color = if (isCurrent) Cream else Neutral100,
                                // 当前播放的那一条要更重一点，扫一眼就知道自己在哪。
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (isCurrent) FontWeight.SemiBold
                                    else FontWeight.Normal,
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isCurrent) {
                                Text(
                                    text = "正在播放",
                                    color = WarmOchre,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
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
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 160, easing = LinearEasing),
        label = "pressScale",
    )
    // 禁用态：整颗按钮压暗，且**不响应点击**。
    //
    // 为什么要有禁用态而不是「点了没反应」：一个看得见却点不动的按钮
    // 会让人以为是 App 坏了。本项目吃过好几次这种亏（通知栏按钮、
    // 后台四个动作键、admin 的「失效音频」按钮…）。
    val alpha by animateFloatAsState(
        targetValue = if (enabled) (if (isPressed) 0.7f else 1f) else 0.28f,
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
                // R7.5：Material 涟漪（原来 indication = null）。
                indication = ripple(),
                enabled = enabled,
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

/** 睡眠定时器的档位（分钟）。0 = 关闭。 */
private val SLEEP_TIMER_OPTIONS = listOf(0, 15, 30, 45, 60)
