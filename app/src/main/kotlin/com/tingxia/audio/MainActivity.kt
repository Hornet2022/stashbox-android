package com.tingxia.audio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tingxia.audio.audio.AudioPlayerService
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.ui.screens.ArticleDetailScreen
import com.tingxia.audio.ui.screens.ArticleListScreen
import com.tingxia.audio.ui.theme.TingxiaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var playerController: PlayerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 让 AudioPlayerService（MediaSessionService）常驻，系统方可接管锁屏 / 通知控制
        startAudioService()
        // 惰性创建底层 ExoPlayer，供 PlayerController 后续播放使用
        playerController.initialize()
        enableEdgeToEdge()
        setContent {
            TingxiaTheme {
                AppNavigation()
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

@Composable
private fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = "list",
    ) {
        composable("list") {
            ArticleListScreen(
                onNavigateToDetail = { id ->
                    navController.navigate("detail/$id")
                },
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
            )
        }
    }
}
