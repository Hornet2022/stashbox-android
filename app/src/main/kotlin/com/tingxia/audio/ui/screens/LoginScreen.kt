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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 登录页（CP4.6 mock 版）。
 *
 * 仅一个「微信登录（mock）」按钮：点击走 [onMockLogin]（ViewModel.mockWechatLogin，
 * 直接返回固定 token）。CP4.7 接真微信 OAuth 时替换为真实授权跳转。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onMockLogin: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
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
            Text("👋 听匣", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("AI 蒸馏你的音频订阅", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(48.dp))
            Button(onClick = onMockLogin) {
                Text("微信登录（mock）")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "CP4.6 mock，CP4.7 接真微信 OAuth",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
