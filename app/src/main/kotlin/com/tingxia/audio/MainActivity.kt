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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.navArgument
import com.tingxia.audio.audio.AudioPlayerService
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
import com.tingxia.audio.ui.settings.SettingsScreen
import com.tingxia.audio.ui.tags.TagSubscriptionScreen
import com.tingxia.audio.ui.theme.TingxiaTheme
import com.tingxia.audio.ui.favorites.FavoritesScreen
import com.tingxia.audio.ui.laterlistens.LaterListensScreen
import com.tingxia.audio.ui.paywall.PaywallScreen
import com.tingxia.audio.data.repository.FavoritesRepository
import com.tingxia.audio.data.repository.FeedbackRepository
import com.tingxia.audio.ui.feedback.FeedbackHistoryScreen
import dagger.hilt.android.AndroidEntryPoint
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 让 AudioPlayerService（MediaSessionService）常驻，系统方可接管锁屏 / 通知控制
        // （共享 ExoPlayer 由 Hilt PlayerModule 提供，PlayerController 与 Service 共用同一实例）
        startAudioService()
        enableEdgeToEdge()
        setContent {
            TingxiaTheme {
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
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE) }
    val hasOnboarded = remember { mutableStateOf(prefs.getBoolean("has_onboarded", false)) }

    LaunchedEffect(Unit) { viewModel.checkLogin() }

    when (state) {
        is AuthState.Checking -> SplashScreen()
        is AuthState.NotLoggedIn,
        is AuthState.Loading,
        is AuthState.Error,
        -> LoginScreen(
            onMockLogin = { uid -> viewModel.mockWechatLogin(uid) },
            errorMessage = (state as? AuthState.Error)?.message,
            isLoading = state is AuthState.Loading,
        )

        is AuthState.LoggedIn -> {
            if (hasOnboarded.value) {
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

    NavHost(
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
                onNavigateToArticleList = { navController.navigate("list") },
                onNavigateToDetail = { id -> navController.navigate("detail/$id") },
                onNavigateToFullscreenPlayer = { navController.navigate("fullscreen_player") },
                onNavigateToOfflineDownload = { navController.navigate("offline_download") },
                onNavigateToSettings = { navController.navigate("settings") },
            )
        }
        composable("list") {
            ArticleListScreen(
                onNavigateToDetail = { id ->
                    navController.navigate("detail/$id")
                },
                onNavigateToTags = {
                    navController.navigate("tags")
                },
                onNavigateToNotifications = {
                    navController.navigate("notifications")
                },
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
                onNavigateToFavorites = { navController.navigate("favorites") },
                onNavigateToLaterListens = { navController.navigate("later-listens") },
                onNavigateToFeedbackHistory = { navController.navigate("feedback-history") },
                initialTag = tag,
                feedbackRepository = feedbackRepository,
            )
        }
        composable("fullscreen_player") {
            FullScreenPlayerScreen(
                // P1-2：title/author 留空占位 — 屏内 collect PlayerController 状态决定显示
                title = "",
                author = null,
                coverUrl = null,
                onDismiss = { navController.popBackStack() },
            )
        }
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
                onBack = { navController.popBackStack() },
                onNavigateToOfflineDownload = { navController.navigate("offline_download") },
                onNavigateToFeedbackHistory = { navController.navigate("feedback-history") },
                onLogout = { authViewModel.logout() },
            )
        }
    }
}
