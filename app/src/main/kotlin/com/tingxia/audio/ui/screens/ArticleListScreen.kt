package com.tingxia.audio.ui.screens

import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.tingxia.audio.data.model.hasAudio
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.repository.FeedbackRepository
import com.tingxia.audio.ui.articles.ArticleListViewModel
import com.tingxia.audio.ui.articles.ShareState
import com.tingxia.audio.ui.components.ShareArticleDialog
import com.tingxia.audio.ui.components.SourceBadge
import com.tingxia.audio.ui.components.StatusBadge
import com.tingxia.audio.ui.feedback.FeedbackBottomSheet
import com.tingxia.audio.util.formatRelativeTime
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 文章列表页。
 *
 * - 顶部 TopAppBar：标题「听匣」+ 标签订阅 / 我的收藏 + ⋮ 溢出菜单
 *   （菜单内含「通知中心」「稍后听」「反馈与建议」「反馈历史」「剪藏」，
 *   见 onNavigateToCapture）。动作图标按 R5.7 控制在「2-3 个 + 溢出菜单」。
 * - 主体 LazyColumn：文章卡片列表（标题 + 来源 + 状态）
 *   · 长按卡片 → 卡片菜单（删除）；读屏另有 customActions 那条路。
 * - 点击卡片 → [onNavigateToDetail]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleListScreen(
    onNavigateToDetail: (String) -> Unit,
    onNavigateToTags: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToLaterListens: () -> Unit,
    onNavigateToFeedbackHistory: () -> Unit,
    // BUG#6：溢出菜单里的「剪藏」入口 → CaptureScreen（HomeScreen 的「剪藏」卡片
    // 和顶部 `+` 按钮走的是同一个 destination）。
    //
    // 默认值是空的：**必须**由 MainActivity 显式传入，否则点它仍然什么都不发生
    // （那正是这个 bug 的原状）。之所以不给它 required，是不想在接线之前就把
    // 整个 App 的编译弄挂 —— 接线只要两行，见菜单项处的注释。
    onNavigateToCapture: () -> Unit = {},
    // CP-TAG-FILTER：可由 TagSubscriptionScreen 跳过来携带 tag=slug
    initialTag: String? = null,
    feedbackRepository: FeedbackRepository? = null,
    // 2026-10-02: 供「上一首/下一首」用。把列表当播放队列灌进去。
    playerController: com.tingxia.audio.audio.PlayerController? = null,
    // 通勤自动预加载的决策层（2026-10-02）
    commutePrefetcher: com.tingxia.audio.data.sync.CommutePrefetcher? = null,
    viewModel: ArticleListViewModel = hiltViewModel(),
) {
    val articles by viewModel.articles.collectAsStateWithLifecycle()

    // 通勤窗口内自动预加载下一集（闭环 3）。决策逻辑在 CommutePrefetcher，
    // 这里只负责喂数据 —— 什么时候**不该**下载由它判断。
    //
    // ⚠️ 这里**不再**把可见文章灌成播放队列（2026-10-23 拆掉）。
    // 那个「隐式队列」是「上一首/下一首常年是灰的」的根源：队列 = 列表页
    // 当前可见的 ready 文章，列表一刷新/翻页/换筛选就变，用户看不见也管不了。
    // 队列现在由**播放列表**驱动 —— 用户显式攒的一串（见 PlaylistRepository），
    // 看得见、管得了、退出重进还在。
    LaunchedEffect(articles, commutePrefetcher) {
        val ready = articles.filter { it.status.hasAudio && !it.audioUrl.isNullOrBlank() }
        if (commutePrefetcher != null && ready.isNotEmpty()) {
            commutePrefetcher.maybePrefetch(articles, learnedWindows = null)
        }
    }
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val activeTag by viewModel.activeTag.collectAsStateWithLifecycle()
    val deletingId by viewModel.deletingId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 0041 共享：列表页共享对话框的状态（列表页没有 ArticleDetailViewModel，独立一份）
    val shareState by viewModel.shareState.collectAsStateWithLifecycle()
    val availableTags by viewModel.availableTags.collectAsStateWithLifecycle()
    val selectedTags by viewModel.selectedTags.collectAsStateWithLifecycle()

    var showFeedbackSheet by remember { mutableStateOf(false) }
    // 原名 showSettingsMenu：菜单里没有一项是「设置」，55a07c0 把它改成 ⋮ 之后
    // 这个名字就在骗人了。跟图标一起改掉。
    var showMoreMenu by remember { mutableStateOf(false) }
    // CP-DELETE: 长按卡片 → 弹出卡片菜单（菜单里才是「删除」）
    var deleteTarget by remember { mutableStateOf<Article?>(null) }
    // CP-DELETE + R6.4/R7.4：删除原来**只有**「长按卡片」这一条路，
    // 读屏（TalkBack）和 Switch Access 用户拿不到等价操作 ——
    // 而 R7.6 明确写了「长按不能是访问某个功能的唯一方式」。
    // 现在补两条路：
    //   1) 长按 → 卡片菜单（菜单里选「删除」），菜单项是可见的、可点的；
    //   2) 卡片语义上挂 customActions → 读屏用户的「操作」菜单里也能删。
    // 两条路最后都收敛到同一个删除确认对话框（deleteTarget），
    // 免得两条路的确认行为不一致。
    var menuTarget by remember { mutableStateOf<Article?>(null) }
    // 0041 共享：点卡片共享图标 → 记下目标文章 → 弹共享对话框
    var shareTarget by remember { mutableStateOf<Article?>(null) }

    // CP-TAG-FILTER：初始 tag 来自 TagSubscriptionScreen 跳转（仅消费一次）
    val initialConsumed = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialTag) {
        if (!initialConsumed.value && initialTag != null) {
            initialConsumed.value = true
            viewModel.setActiveTag(initialTag)
        }
    }

    // CP-DELETE: 错误提示（含删除失败）走 Toast，轻量不打断浏览
    LaunchedEffect(error) {
        if (!error.isNullOrBlank()) {
            android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_SHORT)
                .show()
            // 提示发出后消费掉：否则**同一条错误**再发生时值没变 →
            // 这个 LaunchedEffect 不再触发 → 用户以为第二次成功了。
            viewModel.consumeError()
        }
    }

    if (deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这篇内容？") },
            text = {
                Text(
                    "「${target.title ?: "无标题"}」的蒸馏结果和音频将一并删除，不可恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        viewModel.deleteArticle(target.id)
                    },
                    enabled = deletingId == null,
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    // 0041 共享：成功 → 关对话框 + Toast（共享 / 取消共享）
    LaunchedEffect(shareState) {
        val s = shareState
        if (shareTarget != null && s is ShareState.Success) {
            shareTarget = null
            Toast.makeText(
                context,
                if (s.shared) "已共享" else "已取消共享",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // 0041 共享：打开对话框时预勾选当前 sharedTags（中文名 → id）。
    // 依赖 availableTags 就绪（异步懒加载），所以放在 LaunchedEffect(shareTarget, availableTags)。
    LaunchedEffect(shareTarget, availableTags) {
        val target = shareTarget
        if (target != null && availableTags.isNotEmpty()) {
            val sharedNames = target.sharedTags.orEmpty()
            val ids = availableTags.filter { it.name in sharedNames }.map { it.id }.toSet()
            viewModel.presetSelectedTags(ids)
        }
    }

    // 0041 共享：列表页共享对话框（复用详情页那套标签选择逻辑）
    shareTarget?.let { target ->
        ShareArticleDialog(
            articleTitle = target.title,
            alreadyShared = target.shared,
            sharedTagsCount = target.sharedTags?.size ?: 0,
            availableTags = availableTags,
            selectedTagIds = selectedTags,
            isLoading = shareState is ShareState.Loading,
            errorMessage = (shareState as? ShareState.Error)?.message,
            onToggleTag = viewModel::toggleTag,
            onSave = { selectedIds ->
                // 后端 shared_tags 存的是标签**中文名**，先把 id 映射回名字再提交。
                val names = selectedIds.mapNotNull { id ->
                    availableTags.find { it.id == id }?.name
                }
                if (names.isEmpty()) {
                    viewModel.unshareArticle(target.id)
                } else {
                    viewModel.shareArticle(target.id, names)
                }
            },
            onDismiss = {
                if (shareState !is ShareState.Loading) {
                    shareTarget = null
                    viewModel.clearSelectedTags()
                    viewModel.dismissShareError()
                }
            },
        )
    }

    LaunchedEffect(Unit) {
        viewModel.loadArticles()
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
            feedbackRepository = feedbackRepository,
            appVersion = appVersion,
            onDismiss = { showFeedbackSheet = false },
        )
    }

    // R5.6：顶栏接滚动行为 —— 列表往下滚时顶栏收起，往回滚立刻回来。
    // 用 enterAlways 而不是 pinned：这一屏就是给用户一路往下读用的，
    // 顶上永久挂一条 64dp 的标题栏太浪费纵向空间。
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("听匣") },
                // R5.6：顶栏接滚动行为，列表往下滚时顶栏收起。
                scrollBehavior = scrollBehavior,
                actions = {
                    // 2026-10-03 真机发现：这里原来挂了 6 个动作 —— 4 个
                    // IconButton（标签订阅/通知中心/我的收藏/设置）+ 2 个
                    // TextButton（「稍后听」「添加」）。360dp 屏上 actions 实测
                    // 占掉 320dp，给标题只剩 32dp，于是「听匣」被挤成
                    // 「听」「匣」两行竖排，还压到第一个图标身上。
                    // 和 99e57af 详情页那处是同一类问题：栏里只放得下
                    // 高频动作，低频的收进溢出菜单。
                    //
                    // 2026-10-27（R5.7）：动作图标上限是「2-3 个 + 溢出菜单」。
                    // 原来这栏是 4 个 IconButton（标签订阅/通知中心/我的收藏/⋮），
                    // 注释还写着「留下的 3 个」—— 注释和代码对不上。
                    // 现在留 2 个高频动作 + ⋮：
                    //   · 标签订阅 —— 本 App 的差异化功能（CP-TAG-FILTER）
                    //   · 我的收藏 —— 高频目的地
                    //   · 通知中心 → 收进 ⋮（首页顶栏已有同一个入口，还带未读角标，
                    //     这里再摆一份只是重复）
                    // 「稍后听」「添加」「反馈」原本就在 ⋮ 里，保持不变。
                    //
                    // 图标同时从 ⚙ 改成 ⋮：菜单里装的是反馈/稍后听/添加，
                    // 没有一项是「设置」，齿轮是误导。⋮ 也和详情页一致。
                    //
                    // CP5.3-C: 标签订阅入口
                    IconButton(
                        onClick = onNavigateToTags,
                        modifier = Modifier.semantics { contentDescription = "标签订阅" },
                    ) {
                        Icon(
                            // 2026-10-27：原来用的是 FavoriteBorder（空心心形），
                            // 跟右边实心心形的「我的收藏」几乎一模一样 ——
                            // 两个心形并排，用户只能靠猜。
                            // 换成 Tag（标签），跟「标签订阅」的字面意思对上，
                            // 也不会再跟收藏撞脸。
                            imageVector = Icons.Filled.Tag,
                            // R6.1：语义由 IconButton 上的 contentDescription 承担，
                            // 图标本身是装饰性的 —— 再写一遍读屏会念两遍。
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // CP5.5-B1: 收藏入口
                    IconButton(
                        onClick = onNavigateToFavorites,
                        modifier = Modifier.semantics { contentDescription = "我的收藏" },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(
                        onClick = { showMoreMenu = true },
                        modifier = Modifier.semantics { contentDescription = "更多" },
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                    ) {
                        // CP5.4-C: 通知中心入口（R5.7：从顶栏收进溢出菜单）
                        DropdownMenuItem(
                            text = { Text("通知中心") },
                            leadingIcon = {
                                Icon(Icons.Default.Notifications, contentDescription = null)
                            },
                            onClick = {
                                showMoreMenu = false
                                onNavigateToNotifications()
                            },
                        )
                        // CP5.5-B1: 稍后听入口
                        DropdownMenuItem(
                            text = { Text("稍后听") },
                            onClick = {
                                showMoreMenu = false
                                onNavigateToLaterListens()
                            },
                        )
                        // CP5.5-A3: 反馈入口
                        DropdownMenuItem(
                            text = { Text("反馈与建议") },
                            onClick = {
                                showMoreMenu = false
                                showFeedbackSheet = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("反馈历史") },
                            onClick = {
                                showMoreMenu = false
                                onNavigateToFeedbackHistory()
                            },
                        )
                        // BUG#6（2026-10-06）：原来这里是
                        // `text = "添加（开发中）"` + `onClick = { showMoreMenu = false }`
                        // —— 点它**只关菜单**，什么都不会发生。而剪藏能力早就实现了
                        // （CaptureScreen + D9 分享导入），用户点的是这个 App 的主入口之一，
                        // 却拿到一个自称「开发中」的假入口。
                        //
                        // 现在跳到与 HomeScreen「剪藏」卡片、`+` 按钮**同一条**剪藏路径，
                        // 文案也统一成「剪藏」（CaptureScreen 的标题）。
                        //
                        // ⚠️ 接线的最后一环在 MainActivity：App 的 NavHost 在那里，
                        // 屏内拿不到 NavController（compose 的 NavHost 不像 NavHostFragment
                        // 那样往 View 上打 nav_controller_view_tag，所以本屏不能自己
                        // findNavController()）。两处 ArticleListScreen(...) 调用各加一行：
                        //     onNavigateToCapture = { navController.navigate("capture") }
                        DropdownMenuItem(
                            text = { Text("剪藏") },
                            onClick = {
                                showMoreMenu = false
                                onNavigateToCapture()
                            },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        // ⚠️ 坑（Scaffold + 收缩顶栏）：innerPadding.top 是**静态**的
        // （顶栏高度 + 状态栏 inset），顶栏收起之后它不会变。
        // 原来整块内容用 Modifier.padding(innerPadding) 顶开，代价有两个：
        //   · 列表被永久顶在顶栏下面 —— 永远滚不到顶栏（系统栏）后面（R3.8）；
        //   · 顶栏一收起，屏幕上多出一条 64dp 的空白带，像被抠掉一块。
        // 现在：列表用 contentPadding 带 inset（跟着内容滚走），
        // 固定不动的「仅看标签」提示条自己跟着顶栏的位移量走。
        val density = LocalDensity.current
        val appBarOffset = scrollBehavior.state?.heightOffset ?: 0f
        val barTopInset = with(density) {
            (innerPadding.calculateTopPadding() + appBarOffset.toDp()).coerceAtLeast(0.dp)
        }
        // 列表内容的顶部 inset：提示条在场时它已经占住了顶部，列表别再留一份。
        val listTopInset = if (activeTag != null) 0.dp else innerPadding.calculateTopPadding()
        Column(modifier = Modifier.fillMaxSize()) {
            // CP-TAG-FILTER：active tag 时显示顶部 chip，"仅看 X ✕ 清除"
            activeTag?.let { tag ->
                ActiveTagChip(
                    tag = tag,
                    count = articles.size,
                    onClear = { viewModel.setActiveTag(null) },
                    // 提示条是**固定**的（不在列表里滚），所以它要自己躲开顶栏，
                    // 顶栏收起时跟着抬上去，不然中间会空出一条。
                    modifier = Modifier.padding(top = barTopInset),
                )
            }
            Crossfade(
                targetState = if (isLoading && articles.isEmpty()) "loading" else if (articles.isEmpty()) "empty" else "content",
                animationSpec = tween(300),
                modifier = Modifier.fillMaxSize(),
                label = "article_list_fade",
            ) { state ->
            when (state) {
                "loading" -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(top = listTopInset),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                "empty" -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(top = listTopInset),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "暂无文章",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            // R5.6：把顶栏的滚动行为接到列表上，
                            // 列表下滑时顶栏收起。
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        // R3.8：外边距改成 contentPadding（原来用 Modifier.padding）。
                        // Modifier.padding 是**布局内边距** —— 列表被永久缩进一块：
                        // 既滚不到系统栏后面，末项也顶死在视口边缘（永远差那 8dp）。
                        // contentPadding 跟着内容滚：首尾都能滑到系统栏后面再停下来。
                        // 上下 inset 交给 Scaffold：顶部让开顶栏，底部让开导航栏。
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = listTopInset + 8.dp,
                            bottom = innerPadding.calculateBottomPadding() + 8.dp,
                        ),
                    ) {
                        items(articles, key = { it.id }) { article ->
                            ArticleCard(
                                article = article,
                                isDeleting = deletingId == article.id,
                                menuOpen = menuTarget?.id == article.id,
                                onClick = { onNavigateToDetail(article.id) },
                                onLongClick = { menuTarget = article },
                                onDismissMenu = { menuTarget = null },
                                onDeleteRequest = {
                                    menuTarget = null
                                    deleteTarget = article
                                },
                                onShare = {
                                    // 打开共享对话框：懒加载可用标签 + 记下目标文章
                                    viewModel.loadAvailableTags()
                                    shareTarget = article
                                },
                            )
                        }
                    }
                }
            }
            }  // Crossfade 闭
        }  // Column 闭
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ArticleCard(
    article: Article,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isDeleting: Boolean = false,
    onShare: () -> Unit = {},
    /** 长按弹出的卡片菜单是否展开。 */
    menuOpen: Boolean = false,
    onDismissMenu: () -> Unit = {},
    /** 从卡片菜单或读屏 customActions 走过来的「删除」。 */
    onDeleteRequest: () -> Unit = {},
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                // CP-DELETE: 长按卡片 → 弹出卡片菜单（菜单里才是「删除」）
                // 避免误触，也不用引入滑动删除手势的依赖。
                onLongClick = onLongClick,
            )
            // R6.4 / R7.4：长按动作必须有一条**非手势**的路。
            // combinedClickable 会 mergeDescendants，卡片内部的共享按钮
            // 在读屏里不再是独立可聚焦的节点 —— 所以共享/删除都挂成
            // customActions：读屏用户从「操作」菜单里就能调到，
            // Switch Access / 键盘用户也有一条等价的路。
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("分享") {
                        onShare()
                        true
                    },
                    CustomAccessibilityAction("删除") {
                        onDeleteRequest()
                        true
                    },
                )
            },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Box {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = article.title ?: "无标题",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SourceBadge(source = article.source)
                    StatusBadge(status = article.status)
                    if (isDeleting) {
                        Text(
                            "删除中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                // CP-TIME：列表卡片加创建时间（用户问「这个什么时候加的」一眼能答）
                val relative = formatRelativeTime(article.createdAt)
                if (relative.isNotBlank()) {
                    Text(
                        text = "剪藏于 $relative",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // 0041 共享：底部一行 —— 左侧「已共享」徽标，右侧共享快捷入口。
                // 共享 IconButton 自带 clickable，会消费点击，不会误触发卡片的 onClick（进详情）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (article.shared == true) {
                        Text(
                            text = "已共享",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(
                        onClick = onShare,
                        // ⚠️ 坑：原来这里写的是 Modifier.size(28.dp)，
                        // 把 IconButton 自带的 48dp 最小交互尺寸直接覆盖掉了 ——
                        // 触控区只剩 28×28dp（R6.5 要求 ≥48dp），
                        // 胖手指基本点不中。
                        // 想让图标变小就只改 Icon 的尺寸，别动按钮本身。
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "共享",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            // 长按 → 卡片菜单（R7.6：长按不能是访问功能的唯一方式，
            // 所以这里只是入口之一，读屏还有 customActions 那条路）。
            // DropdownMenu 挂在卡片内部，锚点是卡片自身（菜单出现在卡片左上角）。
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = onDismissMenu,
            ) {
                DropdownMenuItem(
                    text = { Text("删除") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = onDeleteRequest,
                )
            }
        }
    }
}

/**
 * CP-TAG-FILTER：active tag 时的顶部状态条。
 * 显示当前过滤的 slug + 文章数 + 清除按钮。
 * 设计：相比 AssistChip 更显眼的"软提示"，让用户清楚当前看到的是 tag 过滤结果而非全量。
 */
@Composable
private fun ActiveTagChip(
    tag: String,
    count: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = "仅看标签：$tag",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "($count 篇)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            TextButton(
                onClick = onClear,
            ) {
                Text("清除", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
