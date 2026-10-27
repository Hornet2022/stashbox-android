package com.tingxia.audio

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
// 2026-10-21：`var privacyAgreed by remember { mutableStateOf(false) }` 要用 setValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.navArgument
import com.tingxia.audio.BuildConfig
import com.tingxia.audio.audio.AudioPlayerService
import com.tingxia.audio.audio.RequestPlaybackNotificationPermission
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.share.D9Receiver
import com.tingxia.audio.share.D9Result
import com.tingxia.audio.share.D9EventBus
import com.tingxia.audio.ui.auth.AuthState
import com.tingxia.audio.ui.auth.AuthViewModel
import com.tingxia.audio.ui.screens.ArticleDetailScreen
import com.tingxia.audio.ui.screens.ArticleListScreen
import com.tingxia.audio.ui.screens.FullScreenPlayerScreen
import com.tingxia.audio.ui.screens.HomeScreen
import com.tingxia.audio.ui.screens.LoginScreen
import com.tingxia.audio.ui.screens.OnboardingScreen
import com.tingxia.audio.ui.capture.CaptureScreen
import com.tingxia.audio.ui.distill.DistillScreen
import com.tingxia.audio.ui.notifications.NotificationCenterScreen
import com.tingxia.audio.ui.offline.OfflineDownloadScreen
// 2026-10-21：注销账号页 + 隐私政策同意门
import com.tingxia.audio.ui.account.AccountDeletionScreen
import com.tingxia.audio.ui.playlist.PlaylistScreen
import com.tingxia.audio.ui.privacy.PrivacyConsentGate
import com.tingxia.audio.ui.settings.SettingsScreen
import com.tingxia.audio.ui.tags.TagSubscriptionScreen
import com.tingxia.audio.ui.theme.TingxiaTheme
import com.tingxia.audio.ui.components.GlobalPlayerBar
import com.tingxia.audio.ui.favorites.FavoritesScreen
import com.tingxia.audio.ui.laterlistens.LaterListensScreen
import com.tingxia.audio.ui.paywall.PaywallScreen
import com.tingxia.audio.data.repository.FavoritesRepository
import com.tingxia.audio.data.repository.FeedbackRepository
import com.tingxia.audio.ui.feedback.FeedbackHistoryScreen
import dagger.hilt.android.AndroidEntryPoint
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var playerController: PlayerController

    @Inject
    lateinit var favoritesRepository: FavoritesRepository

    @Inject
    lateinit var feedbackRepository: FeedbackRepository

    /** 倍速/音色偏好的启动期加载（2026-10-02）——见 PlaybackPreferenceInitializer。 */
    @Inject
    lateinit var playbackPreferenceInitializer: com.tingxia.audio.data.repository.PlaybackPreferenceInitializer

    @Inject
    lateinit var prefetchScheduler: com.tingxia.audio.data.sync.PrefetchScheduler

    /** 通勤自动预加载决策层 */
    @Inject
    lateinit var commutePrefetcher: com.tingxia.audio.data.sync.CommutePrefetcher

    /** 埋点上报口（首屏加载耗时）。 */
    @Inject
    lateinit var analyticsSink: com.tingxia.audio.data.analytics.AnalyticsSink


    /**
     * 待跳转的路由（2026-10-23 新增）。
     *
     * 通知栏点「播放」要能**回到播放器**，而不是停在首页。
     * 而 Media3 的通知 contentIntent = `mediaSession.getSessionActivity()`，
     * 没设的话 contentIntent 为 null → **点通知什么都不会发生**
     * （DefaultMediaNotificationProvider 的源码就是这么写的：
     * `builder.setContentIntent(mediaSession.getSessionActivity())`）。
     *
     * 为什么不走 deep link（`stashbox://player`）：全项目**没有任何地方**调
     * `navController.handleDeepLink(intent)`，所以清单里声明的
     * `stashbox://detail/{id}` 其实是**不生效**的 —— 靠它导航等于埋一个
     * 看起来能跑、实际不动的机关。走「显式 Intent + extra」最直接：
     * 意图由本 App 自己发出，不依赖系统按 scheme 匹配。
     */
    internal val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 播放偏好必须在**启动时**加载，而不是等某个界面构造 ViewModel。
        // 放在这里是为了让「从任何入口播放」都拿到用户设过的倍速。
        lifecycleScope.launch {
            playbackPreferenceInitializer.ensureLoaded()
            // 顺手补传断网期间攒下的播放进度 + 校准下载台账
            prefetchScheduler.syncNow()
            // 2026-10-24：订阅更新的**本地通知**投递。
            // 没有厂商推送通道时，这是「有更新」能到手机通知栏的唯一通路
            // （周期 15 分钟 + 冷启动立刻跑一次）。
            prefetchScheduler.scheduleSubscriptionNotify()
            prefetchScheduler.notifySubscriptionsNow()
        }

        // 让 AudioPlayerService（MediaSessionService）常驻，系统方可接管锁屏 / 通知控制
        // （共享 ExoPlayer 由 Hilt PlayerModule 提供，PlayerController 与 Service 共用同一实例）
        startAudioService()
        // 冷启动就带着「去播放器」的意图（比如从通知栏点进来）
        pendingRoute.value = intent?.getStringExtra(EXTRA_NAVIGATE_TO)
        enableEdgeToEdge()
        setContent {
            TingxiaTheme {
                // 首屏埋点：必须排在内容**之前**组合 —— 它要等第一帧画出来，
                // 而第一帧里必须已经包含真正的内容（否则量的是「空屏」的耗时）。
                FirstFrameReporter(analyticsSink)
                AuthRoot()
            }
        }
        // CP10.9 D9: 冷启动时检查 intent(微信/抖音/浏览器分享唤起)
        handleD9Intent(intent)
    }

    /**
     * CP10.9 D9: singleTask 模式下,App 已在后台运行 → 复用此 Activity,
     * 新 intent 走 onNewIntent 而非 onCreate。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleD9Intent(intent)
        // 通知栏点「播放」：App 已在后台运行 → singleTask 复用本 Activity，
        // 走 onNewIntent 而不是 onCreate。
        intent.getStringExtra(EXTRA_NAVIGATE_TO)?.let { pendingRoute.value = it }
    }

    /**
     * CP10.9 D9: 接 intent → 解析分享 URL → 调 D9 callback。
     * 非 SEND/VIEW intent 直接忽略。
     */
    private fun handleD9Intent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_VIEW) return
        // 必须异步,不能在 UI 线程跑 HTTP
        lifecycleScope.launch(Dispatchers.IO) {
            val result = D9Receiver.handleIntent(this@MainActivity, intent)
            Log.i("MainActivity", "D9 result: $result")
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startAudioService() {
        try {
            startService(Intent(this, AudioPlayerService::class.java))
        } catch (_: Exception) {
            // 启动失败不影响 UI；CP4.5 会补通知 / 锁屏细节
        }
    }

    companion object {
        /**
         * 「跳到某个路由」的 Intent extra key。
         *
         * 定义在 Activity 这一侧（而不是服务侧）：**导航路由是界面的知识**，
         * 音频服务只负责「告诉系统点通知要去哪儿」。
         */
        const val EXTRA_NAVIGATE_TO = "com.tingxia.audio.NAVIGATE_TO"

        /** 全屏播放器的路由名 —— 与下面 NavHost 里的 `composable("player")` 同一个。 */
        const val ROUTE_PLAYER = "player"
    }
}

/**
 * 首屏加载耗时埋点（2026-10-25）。
 *
 * ## 量的到底是什么
 *
 * **进程启动 → 第一帧真正画出来**。起点取进程启动而不是 `onCreate`，
 * 因为冷启动时 Application / ContentProvider / Hilt 图构建都发生在 onCreate 之前，
 * 那段也是用户等的时间（详见 [FirstFrameTracker] 的说明）。
 *
 * ## 为什么用 `withFrameNanos` 而不是 `onCreate` 里直接算
 *
 * `withFrameNanos` 挂起到**下一帧绘制完成**那一刻 —— 那才是「用户看到东西了」。
 * 在 onCreate 里直接取时间只能量到「Activity 创建完了」，用户还在看黑屏/白屏。
 *
 * ## 为什么放在 AuthRoot **之前**组合
 *
 * 顺序决定了第一帧里有什么：先组合这个占位组件、再组合真正的内容，
 * 第一帧就包含内容。顺序反了的话，量到的是「只有空组件的那一帧」。
 *
 * ## 为什么每个进程只报一次
 *
 * 冷启动一次 = 一条记录。重复上报会把 P95 拉成「同一台手机报了好几次」的假象。
 */
@Composable
private fun FirstFrameReporter(sink: com.tingxia.audio.data.analytics.AnalyticsSink) {
    val tracker = remember { com.tingxia.audio.data.analytics.FirstFrameTracker() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        tracker.consumeFirstFrame()?.let { durationMs ->
            sink.report(
                com.tingxia.audio.data.analytics.EventNames.APP_FIRST_FRAME,
                mapOf("duration_ms" to durationMs),
            )
        }
    }
}

/**
 * 鉴权路由根：启动检查 token，决定首屏是登录页还是内容页。
 *
 * CP5.1 新增：已登录用户看 SharedPreferences has_onboarded 标志，
 * 未看过引导则显示 OnboardingScreen（3 步），看完设标志。
 * 引导状态本地存 SharedPreferences（CP6.7 再统一服务端化）。
 */
@Composable
private fun AuthRoot() {
    val viewModel: AuthViewModel = hiltViewModel()
    // collectAsStateWithLifecycle：AuthRoot 在后台时不必继续收 auth 状态
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE) }
    val hasOnboarded = remember { mutableStateOf(prefs.getBoolean("has_onboarded", false)) }

    // 2026-10-21：隐私政策同意是否已通过。
    //
    // 放在 AuthRoot 层级（而不是 LoggedIn 分支里）是有意的：
    // 放分支里的话，退出登录再登录会让它重置为 false，
    // 用户会**再看一次**同意页（虽然服务端说"你已经同意过"，
    // 但那中间还是会闪一下空白/加载态）。
    var privacyAgreed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.checkLogin() }

    // 播放通知的权限（2026-10-23 新增）。
    //
    // Android 13+ 没这个权限时的现象是「**声音在播但通知栏没有控制条**」
    // —— 前台服务照跑、声音照出，用户却没法在通知栏暂停、没法锁屏控制。
    // 申请时机选在「用户第一次播放」（见 RequestPlaybackNotificationPermission
    // 的说明），不选在一进 App。
    RequestPlaybackNotificationPermission((LocalContext.current as MainActivity).playerController)

    // 会话在后台过期时，必须有人把 AuthRoot 拉回登录页。
    //
    // 原来只有上面那一次 checkLogin（LaunchedEffect(Unit)，冷启动跑一次就再也不跑），
    // 所以 AuthState 在整个进程生命周期里只被算过这一次：access token 过期后，
    // AuthInterceptor 清掉 token 但 AuthState 仍是 LoggedIn —— 用户停在文章列表上
    // 看起来还登着，每个请求都 401，而且**再也回不到登录页**，不杀进程就恢复不了。
    //
    // 现在 AuthInterceptor 在 refresh 确定失败时发 SessionExpirySignal，这里收到就把
    // 状态翻回未登录，上面的 when 自动重渲染 LoginScreen（无需导航：登录页不是
    // NavHost 的 destination）。
    LaunchedEffect(Unit) {
        viewModel.sessionExpired.collect { viewModel.onSessionExpired() }
    }

    when (state) {
        is AuthState.Checking -> SplashScreen()
        is AuthState.NotLoggedIn,
        is AuthState.Loading,
        is AuthState.Error,
        -> LoginScreen(
            onLogin = { code -> viewModel.login(code) },
            onDevLogin = if (BuildConfig.DEBUG) { uid -> viewModel.devLoginAs(uid) } else null,
            errorMessage = (state as? AuthState.Error)?.message,
            isLoading = state is AuthState.Loading,
        )

        is AuthState.LoggedIn -> {
            // 2026-10-21：隐私政策同意门。
            //
            // 顺序：**同意 → 引导**。合规上"同意"要在用之前拿到，
            // 所以它排在产品引导前面 —— 先让用户知道我们收集什么，再教他怎么用。
            //
            // 为什么用 `if (!privacyAgreed)` 包住：
            // 门通过后要把整个门从组合里摘掉，否则它那层 fillMaxSize 的空 Box
            // 会把下面的内容顶出屏幕。
            if (!privacyAgreed) {
                PrivacyConsentGate(onAgreed = { privacyAgreed = true })
            } else if (hasOnboarded.value) {
                AppNavigation()
            } else {
                OnboardingScreen(
                    onCompleted = {
                        prefs.edit().putBoolean("has_onboarded", true).apply()
                        hasOnboarded.value = true
                    },
                )
            }
        }
    }
}

/** 启动检查中的占位页（CP4.6 简易版，后续可换品牌闪屏）。 */
@Composable
private fun SplashScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text("听匣", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun AppNavigation() {
    val navController = rememberNavController()
    val activity = LocalContext.current as MainActivity
    val favoritesRepository = activity.favoritesRepository
    val feedbackRepository = activity.feedbackRepository
    // 2026-10-02: 播放队列需要它（上一首/下一首）
    val playerController = activity.playerController
    val commutePrefetcher = activity.commutePrefetcher

    // 退出登录必须操作 **AuthRoot 那个** AuthViewModel（Activity 作用域），
    // 这里的 hiltViewModel() 在 NavHost 之外解析，拿到的仍是同一实例。
    //
    // 关键：退出登录 **不做任何导航**。登录页不是 NavHost 的 destination，
    // 它是 AuthRoot 根据 state 分支渲染的（见 AuthRoot 的 when）。
    // 之前写成 navController.navigate("login") 有两个致命问题：
    //   1. 导航图里根本没有 "login" 路由 → navigate 抛 IllegalArgumentException
    //      → 进程直接崩，点一次退出登录 App 就没了；
    //   2. 就算路由存在，导航也切不动登录态 —— AuthRoot 仍持有 AuthState.LoggedIn。
    //
    // 正确做法：清 token + 置 AuthState.NotLoggedIn，AuthRoot 自动换回 LoginScreen，
    // 同时整个 AppNavigation 子树被销毁。
    val authViewModel: AuthViewModel = hiltViewModel()

    // 通知栏点「播放」→ 跳到全屏播放器（2026-10-23 新增）。
    //
    // 用 collect 而不是 collectAsStateWithLifecycle：这是一次性的「命令」，
    // 不是界面状态 —— 跳完就要清空，否则转屏回来会又跳一次。
    LaunchedEffect(Unit) {
        activity.pendingRoute.collect { route ->
            if (route != null) {
                navController.navigate(route) {
                    launchSingleTop = true
                }
                activity.pendingRoute.value = null
            }
        }
    }

    // P1-3：D9 分享结果订阅 → toast + 跳转文章列表
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        D9EventBus.events.collect { result ->
            val msg = when (result) {
                is D9Result.Success -> "已加入蒸馏队列：${result.articleId.take(12)}…"
                is D9Result.Error -> "分享导入失败：${result.reason}"
            }
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            // 跳到文章列表让用户能立即看到刚导入的条目
            navController.navigate("list") {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    // 全局迷你播放条（2026-10-27）：
    //
    // 需求：只要**有音频在播**，播放器控件就要始终可见，而不是只在详情页。
    // 做法：在 NavHost 之上叠一层全局 [GlobalPlayerBar]。它读的是
    // PlayerController 单例的真实曲目，所以在任何页面都显示当前在播的那一篇。
    // 唯一例外是全屏播放页 —— 它本身就是展开的控件，再叠一条迷你条是重复的
    // （见 GlobalPlayerBar 里对 route == "player" 的判断）。
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 迷你播放条要占的高度（实测，不含系统导航栏 inset）。
    //
    // 播放条是**浮层**（盖在 NavHost 之上），所以内容区必须自己让出这块高度 ——
    // 否则列表滚到最后一项时，它正好落在播放条底下，用户怎么滚都看不全。
    //
    // 为什么扣掉导航栏 inset：各页自己的 Scaffold 已经按 systemBars 内缩过一次，
    // 这里再计一遍就多出一截空白（导航栏那么高）。
    var playerBarHeight by remember { mutableStateOf(Dp.Unspecified) }
    val contentBottomPadding = if (playerBarHeight == Dp.Unspecified) {
        0.dp
    } else {
        playerBarHeight
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 背景色必须和页面的 Scaffold 底色一致（各页 Scaffold 默认就是
            // colorScheme.background）—— 否则给播放条预留出来的那条空隙
            // 会露出窗口底色，看着像一条缝。
            .background(MaterialTheme.colorScheme.background),
    ) {
        NavHost(
            modifier = Modifier.padding(bottom = contentBottomPadding),
            navController = navController,
            startDestination = "home",
        ) {
            composable("home") {
            HomeScreen(
                onNavigateToFavorites = { navController.navigate("favorites") },
                onNavigateToNotifications = { navController.navigate("notifications") },
                onNavigateToCapture = { navController.navigate("capture") },
                onNavigateToDistill = { navController.navigate("distill") },
                onNavigateToSubscribe = { navController.navigate("tags") },
                onNavigateToReview = { navController.navigate("later-listens") },
                onNavigateToPlaylist = { navController.navigate("playlist") },
                onNavigateToArticleList = { navController.navigate("list") },
                onNavigateToDetail = { id -> navController.navigate("detail/$id") },
                onNavigateToOfflineDownload = { navController.navigate("offline_download") },
                onNavigateToSettings = { navController.navigate("settings") },
            )
        }
        composable("list") {
            ArticleListScreen(
                playerController = playerController,
                commutePrefetcher = commutePrefetcher,
                onNavigateToDetail = { id ->
                    navController.navigate("detail/$id")
                },
                onNavigateToTags = {
                    navController.navigate("tags")
                },
                onNavigateToNotifications = {
                    navController.navigate("notifications")
                },
                // 「剪藏」菜单项原来只关菜单，是个死的 affordance（CP4.6 的 TODO）。
                // 剪藏屏本来就已经在导航图里，缺的只是这一根接线。
                onNavigateToCapture = { navController.navigate("capture") },
                onNavigateToFavorites = {
                    navController.navigate("favorites")
                },
                onNavigateToLaterListens = {
                    navController.navigate("later-listens")
                },
                onNavigateToFeedbackHistory = {
                    navController.navigate("feedback-history")
                },
                feedbackRepository = feedbackRepository,
            )
        }
        composable(
            route = "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = "stashbox://detail/{id}" }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id").orEmpty()
            ArticleDetailScreen(
                articleId = id,
                onBack = { navController.popBackStack() },
                onOpenFullScreenPlayer = {
                    navController.navigate("player")
                },
                favoritesRepository = favoritesRepository,
                feedbackRepository = feedbackRepository,
            )
        }
        composable("player") {
            FullScreenPlayerScreen(
                // P1-2：title/author 留空占位 — 屏内 collect PlayerController 状态决定显示
                title = "",
                author = null,
                coverUrl = null,
                // 2026-10-03：底部 4 个动作键此前全是空实现（isFavorite 恒 false、
                // 四个回调都是默认的 {}），四个可见控件点了没有任何反应。
                // 这里把两个仓库传下去，屏内自己接线：收藏走乐观更新，
                // 反馈走 FeedbackBottomSheet。
                favoritesRepository = favoritesRepository,
                feedbackRepository = feedbackRepository,
                onDismiss = { navController.popBackStack() },
            )
        }
        composable("tags") {
            TagSubscriptionScreen(
                onBack = { navController.popBackStack() },
                // CP-TAG-FILTER：点击 tag 行 → 跳 article_list?tag=slug
                onTagClick = { slug ->
                    navController.navigate("article_list?tag=$slug")
                },
                // 2026-10-23：订阅闭环的「读回」一环 —— 点订阅更新里的文章进详情页
                onArticleClick = { articleId ->
                    navController.navigate("detail/$articleId")
                },
            )
        }
        composable("notifications") {
            NotificationCenterScreen(
                onBack = { navController.popBackStack() },
                onNavigateToArticle = { articleId ->
                    navController.navigate("detail/$articleId")
                },
            )
        }
        composable("favorites") {
            FavoritesScreen(
                onBack = { navController.popBackStack() },
                onNavigateToDetail = { articleId ->
                    navController.navigate("detail/$articleId")
                },
            )
        }
        composable("playlist") {
            PlaylistScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable("later-listens") {
            LaterListensScreen(
                onBack = { navController.popBackStack() },
                onNavigateToDetail = { articleId ->
                    navController.navigate("detail/$articleId")
                },
            )
        }
        composable("feedback-history") {
            FeedbackHistoryScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable("capture") {
            CaptureScreen(
                onBack = { navController.popBackStack() },
                onCaptured = { navController.popBackStack() },
                onQuotaExhausted = {
                    // CP11.0.4 P1.2: 配额用尽 → 跳付费墙
                    navController.navigate("paywall")
                },
                // BUG#10：最近剪藏的卡片原先不可点，补上详情跳转
                onNavigateToDetail = { articleId ->
                    navController.navigate("detail/$articleId")
                },
            )
        }
        composable("distill") {
            DistillScreen(
                onBack = { navController.popBackStack() },
                onDistilled = { navController.popBackStack() },
            )
        }
        // CP-TAG-FILTER：带可选 tag 参数的路由（从 TagSubscriptionScreen 跳转）
        composable(
            route = "article_list?tag={tag}",
            arguments = listOf(
                navArgument("tag") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val tag = entry.arguments?.getString("tag")
            ArticleListScreen(
                onNavigateToDetail = { id -> navController.navigate("detail/$id") },
                onNavigateToTags = { navController.navigate("tags") },
                onNavigateToNotifications = { navController.navigate("notifications") },
                onNavigateToCapture = { navController.navigate("capture") },
                onNavigateToFavorites = { navController.navigate("favorites") },
                onNavigateToLaterListens = { navController.navigate("later-listens") },
                onNavigateToFeedbackHistory = { navController.navigate("feedback-history") },
                initialTag = tag,
                feedbackRepository = feedbackRepository,
                // 2026-10-03：这两条原来没传。
                // playerController 决定「上一首/下一首」有没有队列可走 ——
                //   队列就是由这个参数灌进去的。不传的话，从「主题标签」
                //   点进来这一屏，播放器里这两颗键是灰的。
                // commutePrefetcher 决定通勤窗口到点时会不会预加载 ——
                //   不传的话同一条路径上通勤预加载整个不工作。
                // 也就是说同一个 ArticleListScreen，走「全部」进来能用、
                // 走「某个标签」进来就哑了，而用户看不出这两个入口的区别。
                playerController = playerController,
                commutePrefetcher = commutePrefetcher,
            )
        }
        // 2026-10-03：删掉 fullscreen_player 路由。它和上面的 "player"
        // 指向同一个 FullScreenPlayerScreen、参数逐字相同，是同一条路注册了两次；
        // 而唯一能进播放器的入口是详情页的 onOpenFullScreenPlayer → "player"。
        // HomeScreen 上的 onNavigateToFullscreenPlayer 参数从来没有被调用过，
        // 整条路是死的。
        // CP11.0.4 P1.2: 付费墙
        composable("paywall") {
            PaywallScreen(
                onBack = { navController.popBackStack() },
            )
        }
        // CP7.4.0: 通勤预加载（§3.2 warm 客户端入口）
        composable("offline_download") {
            OfflineDownloadScreen(
                onBack = { navController.popBackStack() },
            )
        }
        // 设置页（CP5.6.0 / §4 G2 个性化开关 UI 灰置）
        composable("settings") {
            SettingsScreen(
                feedbackRepository = feedbackRepository,
                onBack = { navController.popBackStack() },
                onNavigateToOfflineDownload = { navController.navigate("offline_download") },
                onNavigateToFeedbackHistory = { navController.navigate("feedback-history") },
                // 2026-10-21：注销账号入口（危险操作，单独隔在设置页底部红区）
                onNavigateToAccountDeletion = { navController.navigate("account-deletion") },
                onLogout = { authViewModel.logout() },
            )
        }
        // 2026-10-21：注销账号页。
        //
        // 注销是不可逆的终局操作，所以它在**自己的页面**里（要打字确认），
        // 而不是设置页里弹个对话框。设置页里那个入口还会先弹一次确认，
        // 两层确认是故意的：误触一次不该丢掉全部数据。
        composable("account-deletion") {
            AccountDeletionScreen(
                onBack = { navController.popBackStack() },
                // 注销完成后由 AuthViewModel 把登录态翻回未登录 ——
                // 和「退出登录」走同一条路（AuthRoot 按 state 分支渲染登录页，
                // 登录页不是 NavHost 的 destination，所以这里**不能** navigate）。
                //
                // 用 onAccountDeleted() 而不是 logout()：注销流程里
                // AccountRepository.logoutLocalOnly() 已经清了 token + 本机数据，
                // 再走一遍 logout() 会多打一次注定 401 的服务端请求。
                onAccountDeleted = { authViewModel.onAccountDeleted() },
            )
        }
        }

        // 全局迷你播放条：叠在 NavHost 之上，底部对齐。
        //
        // onContentHeightChange：把条子实测高度报回来，让 NavHost 预留出
        // 对应空间（见上面 contentBottomPadding 的说明）。
        GlobalPlayerBar(
            playerController = playerController,
            currentRoute = currentRoute,
            onClick = {
                navController.navigate("player") {
                    launchSingleTop = true
                }
            },
            onContentHeightChange = { playerBarHeight = it },
        )
    }
}
