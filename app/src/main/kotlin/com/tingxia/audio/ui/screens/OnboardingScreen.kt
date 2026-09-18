package com.tingxia.audio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingxia.audio.onboarding.OnboardingState
import com.tingxia.audio.onboarding.OnboardingViewModel

private data class OnboardingStep(
    val title: String,
    val body: String,
)

private val steps = listOf(
    OnboardingStep(
        title = "复制链接",
        body = "在公众号 / 抖音 / 任意网页看到喜欢的文章，长按复制链接",
    ),
    OnboardingStep(
        title = "打开听匣",
        body = "在浏览器打开听匣，自动接收链接，开始 30 秒蒸馏",
    ),
    OnboardingStep(
        title = "听蒸馏音频",
        body = "30 秒后听 30 分钟精华，随时回听、收藏、订阅",
    ),
)

/**
 * 引导页（CP5.1 客户端半版）。
 *
 * 3 步引导页 + 进度指示器 + 下一步/完成按钮。
 * 引导状态本地存 SharedPreferences（CP6.7 再统一服务端化）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onCompleted: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var currentStep by remember { mutableIntStateOf(1) }

    LaunchedEffect(Unit) {
        viewModel.onStart()
    }

    LaunchedEffect(currentStep) {
        viewModel.onStepViewed(currentStep)
    }

    Scaffold(
        topBar = {
            LinearProgressIndicator(
                progress = { currentStep / 3f },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (currentStep > 1) {
                    TextButton(onClick = { currentStep-- }) {
                        Text("上一步")
                    }
                }
                Button(
                    onClick = {
                        if (currentStep < 3) {
                            currentStep++
                        } else {
                            viewModel.onComplete(onSuccess = onCompleted)
                        }
                    },
                    enabled = state !is OnboardingState.Error,
                ) {
                    Text(if (currentStep < 3) "下一步" else "完成")
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            val step = steps[currentStep - 1]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text("步骤 $currentStep / 3", style = MaterialTheme.typography.labelLarge)
                Text(step.title, style = MaterialTheme.typography.headlineLarge)
                Text(step.body, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
