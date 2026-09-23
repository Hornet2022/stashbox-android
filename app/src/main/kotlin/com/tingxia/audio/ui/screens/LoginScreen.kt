package com.tingxia.audio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tingxia.audio.BuildConfig

/**
 * 登录页（CP7.4 真后端版）。
 *
 * 仅一个「微信登录」按钮：点击走 [onMockLogin]（AuthRepository.mockWechatLogin，
 * CP7.4 已改为调 backend POST /api/v1/auth/wechat-login 拿真 JWT，不再造假 token。
 * 按钮文案保留"mock"字样，因微信 OAuth AppID 尚未申请，接入时替换为真实授权跳转。
 *
 * debug 构建额外暴露一个 user_id 输入框，便于联调多账号（P0-3：不再写死 6892）。
 * 留空则使用 [BuildConfig.DEBUG_USER_ID] 默认值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onMockLogin: (String?) -> Unit = {},
    errorMessage: String? = null,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val defaultUserId = BuildConfig.DEBUG_USER_ID
    var userIdInput by remember { mutableStateOf(defaultUserId) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("登录听匣") }) },
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("听匣", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("AI 蒸馏你的音频订阅", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(48.dp))
            if (BuildConfig.DEBUG) {
                OutlinedTextField(
                    value = userIdInput,
                    onValueChange = { userIdInput = it },
                    label = { Text("调试 user_id（联调多账号）") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            Button(
                onClick = {
                    val uid = userIdInput.takeIf { it.isNotBlank() }
                    onMockLogin(uid)
                },
                enabled = !isLoading,
            ) {
                Text(if (isLoading) "登录中..." else "微信登录（mock）")
            }
            if (errorMessage != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "登录失败：$errorMessage",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "微信 OAuth AppID 待申请（CP7.4 已通后端 JWT）",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
