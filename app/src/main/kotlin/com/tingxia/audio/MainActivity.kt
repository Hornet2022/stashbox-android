package com.tingxia.audio

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tingxia.audio.audio.AudioPlayerService
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.ui.auth.AuthState
import com.tingxia.audio.ui.auth.AuthViewModel
import com.tingxia.audio.ui.screens.ArticleDetailScreen
import com.tingxia.audio.ui.screens.ArticleListScreen
import com.tingxia.audio.ui.screens.LoginScreen
import com.tingxia.audio.ui.screens.OnboardingScreen
import com.tingxia.audio.ui.notifications.NotificationCenterScreen
import com.tingxia.audio.ui.tags.TagSubscriptionScreen
import com.tingxia.audio.ui.theme.TingxiaTheme
import com.tingxia.audio.ui.favorites.FavoritesScreen
import com.tingxia.audio.ui.laterlistens.LaterListensScreen
import com.tingxia.audio.data.repository.FavoritesRepository
import com.tingxia.audio.data.repository.FeedbackRepository
import com.tingxia.audio.ui.feedback.FeedbackHistoryScreen
import dagger.hilt.android.AndroidEntryPoint
import androidx.hilt.navigation.compose.hiltViewModel
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
        startAudioService()
        // 惰性创建底层 ExoPlayer，供 PlayerController 后续播放使用
        playerController.initialize()
        enableEdgeToEdge()
        setContent {
            TingxiaTheme {
                AuthRoot()
            }
        }
    }

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
        -> LoginScreen(onMockLogin = viewModel::mockWechatLogin)

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

    NavHost(
        navController = navController,
        startDestination = "list",
    ) {
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
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id").orEmpty()
            ArticleDetailScreen(
                articleId = id,
                onBack = { navController.popBackStack() },
                favoritesRepository = favoritesRepository,
                feedbackRepository = feedbackRepository,
            )
        }
        composable("tags") {
            TagSubscriptionScreen(
                onBack = { navController.popBackStack() },
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
    }
}
