package com.tingxia.audio.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.ui.articles.ArticleListViewModel
import com.tingxia.audio.ui.components.NotificationBadgeIcon
import com.tingxia.audio.ui.components.sourceLabelRes
import com.tingxia.audio.ui.notifications.NotificationBadgeViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

data class SkillItem(
    val title: String,
    val icon: ImageVector,
    val route: String,
)

val skillItems = listOf(
    SkillItem("剪藏", Icons.Default.ContentCut, "capture"),
    SkillItem("蒸馏", Icons.Default.Headphones, "distill"),
    SkillItem("订阅", Icons.Default.Subscriptions, "subscribe"),
    SkillItem("回听", Icons.Default.Headphones, "review"),
    SkillItem("通勤预加载", Icons.Default.CloudDownload, "offline"),
    // 2026-10-23：播放列表入口。
    //
    // 队列是**用户攒的一串**（见 PlaylistRepository），所以它得有个地方能去 ——
    // 之前根本没有这个概念，队列是「文章列表页当前可见的那些」这个隐式东西。
    SkillItem("播放列表", Icons.Default.PlaylistPlay, "playlist"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToFavorites: () -> Unit = {},
    onNavigateToNotifications: () -> Unit = {},
    onNavigateToCapture: () -> Unit = {},
    onNavigateToDistill: () -> Unit = {},
    onNavigateToSubscribe: () -> Unit = {},
    onNavigateToReview: () -> Unit = {},
    /** 播放列表（2026-10-23）—— 队列的用户可见形态。 */
    onNavigateToPlaylist: () -> Unit = {},
    onNavigateToArticleList: () -> Unit = {},
    onNavigateToDetail: (String) -> Unit = {},
    onNavigateToOfflineDownload: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    viewModel: ArticleListViewModel = hiltViewModel(),
    badgeViewModel: NotificationBadgeViewModel = hiltViewModel(),
) {
    val articles by viewModel.articles.collectAsStateWithLifecycle()
    // 未读角标（2026-10-23）。进首页时拉一次；Navigation 离开再回来
    // 会重建 composition → LaunchedEffect(Unit) 重跑 → 数字是最新的。
    val unreadCount by badgeViewModel.unreadCount.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { badgeViewModel.refresh() }
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadArticles()
    }

    // R5.6：顶栏接滚动行为 —— 文章列表往下滚的时候顶栏收起，把纵向空间让给内容。
    // 用 enterAlways 而不是 pinned：往下滚就藏，往回滚立刻回来，
    // 列表页（全文列表）用户一路往下读的时候不用一直顶着一个 64dp 的标题栏。
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("听匣") },
                scrollBehavior = scrollBehavior,
                actions = {
                    // R5.7：顶栏动作图标最多 2-3 个。
                    //
                    // 2026-10-27（用户拍板）：顶栏**只保留三个入口** ——
                    // 收藏、通知、设置。
                    //
                    // 为什么「剪藏」从顶栏彻底拿掉（而不是收进 ⋮）：
                    //   · 剪藏在下面的技能网格里有一张**大卡片**入口（首屏最显眼处），
                    //     顶栏再放一份是重复入口；
                    //   · 保留 ⋮ 菜单只为装「剪藏」不值得 —— 上一版是
                    //     「收藏+通知+⋮(剪藏、设置)」，点 ⋮ 只有一个「剪藏」是活的，
                    //     「设置」被埋在第二项，用户想改个主题还得先点开菜单再点第二项；
                    //   · 三个常驻图标（收藏/通知/设置）在 360dp 屏上是 3×48dp=144dp，
                    //     标题「听匣」还剩约 180dp，够用。
                    //
                    // ⚠️ 「设置」是**必须常驻**的：它装着主题开关（浅色/深色/跟随系统），
                    // 埋在菜单里等于把「换个主题」变成两次点击。
                    IconButton(onClick = onNavigateToFavorites) {
                        Icon(Icons.Filled.Favorite, contentDescription = "收藏")
                    }
                    // 2026-10-27：角标**不再叠在铃铛上**（用户反馈"通知按钮被
                    // 角标完全遮挡"）。
                    //
                    // ## 叠放方案为什么不可行（真机实测，非推测）
                    //
                    // 原来角标用 `align(Alignment.TopEnd)` 盖在图标右上角，而
                    //   图标 24dp × 角标 20dp ⇒ 角标**完全落在图标框内**，
                    //   重叠面积占图标的 **69%**。
                    // 逐像素统计那个 20×20dp 的角标区域：
                    //   角标底色铺 3530px、数字 900px，
                    //   **只剩 83px 是铃铛线条**（角标区 4900px 的 1.7%）
                    // ⇒ 铃铛几乎整个消失。
                    // 而缩小角标会让 "99+" 被裁成 "99"/"9+" 这种**错数**，
                    // 角标数字被当成真实未读数，比看不清更糟。
                    //
                    // ⇒ 改为**紧贴铃铛右侧并排**（🔔 [1]）：零遮挡、20dp 完整可读，
                    // 也不需要"在 TopAppBar 背景色上挖环"那套背景色耦合
                    // （顶栏滚动时背景会从 surface 变 surfaceContainer）。
                    //
                    // 组件与布局契约见 NotificationBadgeIcon.kt，
                    // 护栏见 NotificationBadgeIconTest.kt。
                    NotificationBadgeIcon(
                        count = unreadCount,
                        onClick = onNavigateToNotifications,
                    )
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        }
    ) { innerPadding ->
        // ⚠️ 坑（Scaffold + 收缩顶栏）：innerPadding.top 是**静态**的
        // （顶栏高度 + 状态栏 inset），顶栏收起之后它不会变。
        // 直接 padding(innerPadding) 的话，内容会原地不动、屏幕上多出
        // 一条 64dp 的空白带，顶栏像被"抠掉了一块"。
        // 解决办法是跟着顶栏自己的位移量一起收回来：
        // heightOffset 收起到顶时是负数（= 顶栏高度），加回去正好抵掉。
        val density = LocalDensity.current
        val appBarOffset = scrollBehavior.state?.heightOffset ?: 0f
        val topInset = with(density) {
            (innerPadding.calculateTopPadding() + appBarOffset.toDp()).coerceAtLeast(0.dp)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = topInset,
                    bottom = innerPadding.calculateBottomPadding(),
                )
                // R5.6：把顶栏的滚动行为接到滚动容器链上。
                // 挂在 Column（两个滚动容器的父节点）上，网格和文章列表
                // 两边滚都会带动顶栏。
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            // 技能网格（2x2 → 自适应列数）
            //
            // R3.1/响应式：列数改成 GridCells.Adaptive(minSize = 160.dp)。
            // 原来是 Fixed(2) —— 宽屏（平板、横屏）上永远是两列，
            // 每张卡被拉到半屏宽，卡片里的图标和字孤零零地挂在中间。
            //
            // ⚠️ 坑：Adaptive 的列数算法是
            //     列数 = (可用宽 + 间距) / (minSize + 间距)
            // 360dp 手机上可用宽 = 360 - 16×2 = 328dp：
            //     间距 12dp → (328+12)/(160+12) = 1.97 → **1 列**（整屏只剩一张巨卡）
            //     间距 8dp  → (328+8)/(160+8)  = 2.00 → 2 列 ✓
            // 所以间距必须用 8dp（Material 紧凑布局的 gutter 本来也是 8dp，R3.2）。
            // 宽屏自然往上走：600dp 屏 3 列、840dp 屏 4 列。
            //
            // 边距统一到 16dp（任务项 4 / R3.5）：原来是
            // Modifier.padding(16.dp) + contentPadding(4.dp) = 实际左右 20dp，
            // 而下面的文章列表是 16dp —— 同一屏两套边距，网格和列表对不齐。
            // 现在横竖都用 contentPadding 16dp，一套值。
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(skillItems) { skill ->
                    SkillCard(
                        skill = skill,
                        onClick = {
                            when (skill.route) {
                                "capture" -> onNavigateToCapture()
                                "distill" -> onNavigateToDistill()
                                "subscribe" -> onNavigateToSubscribe()
                                "review" -> onNavigateToReview()
                                "offline" -> onNavigateToOfflineDownload()
                                "playlist" -> onNavigateToPlaylist()
                            }
                        }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Article List
            //
            // 「文章列表」是进列表页的**唯一**入口。
            // 原来是一行纯 Text + clickable：触控区只有字高（≈24dp），
            // 而且没有 contentDescription —— 读屏只念出一个没有动作含义的词，
            // 用户不知道点它会发生什么。
            // 现在换成 ListItem：自带图标 + 箭头 + 56dp 以上的行高，
            // 语义用 contentDescription 描述**动作**（R6.2：说"查看全部文章"，
            // 不说"文章列表这几个字"）。
            ListItem(
                headlineContent = {
                    Text(
                        text = "文章列表",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // R6.5：触控区下限 48dp。ListItem 自身已经够高，
                    // 这里再写死一道是防止以后换成更矮的内容布局时踩坑。
                    .heightIn(min = 48.dp)
                    .clickable { onNavigateToArticleList() }
                    .semantics { contentDescription = "查看全部文章" },
            )

            when {
                isLoading && articles.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                articles.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(articles, key = { it.id }) { article ->
                            ArticleListItem(
                                article = article,
                                onClick = { onNavigateToDetail(article.id) },
                            )
                            if (articles.last() != article) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillCard(skill: SkillItem, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "skill_card_scale"
    )

    Card(
        modifier = Modifier
            .scale(scale)
            // 2026-10-03：原来 `clickable { pressed = true; onClick() }` ——
            // pressed 只被置 true，永远没有复位的那一行。点一次之后，
            // 卡片会缩到 0.95 并**卡在那个尺寸**，缩到一半的卡片比
            // 静止时更显得没做完。正确做法是让 interactionSource 驱动
            // 按下态，抬手/取消/移出时由框架自己复位。
            .clickable(
                interactionSource = interactionSource,
                // R7.5：Material 涟漪（原来 indication = null）。
                // 卡片自绘了 scale 按压动画，但**没有涟漪** ——
                // 用户按下去看不到"按在卡片哪一块"。
                indication = ripple(),
            ) { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = skill.icon,
                // R6.1：图标是**装饰性**的 —— 紧挨着下面同名的 Text。
                // contentDescription 写 title 的话，读屏会把同一个词念两遍
                // （"剪藏，剪藏"），反而更难分辨哪个才是能点的。
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = skill.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ArticleListItem(article: Article, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Column {
            Text(
                text = article.title ?: "无标题",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(sourceLabelRes(article.source)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
