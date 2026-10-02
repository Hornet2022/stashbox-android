package com.tingxia.audio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding

/**
 * 登录页。
 *
 * ## 两条路径（2026-10-02 修正）
 *
 * 之前只有一个按钮，且无论 release / debug 都调 gateway 的 `/api/v1/auth/token`
 * ——那是个**无鉴权后门**（传任意 user_id 换真 token），现已默认关闭。
 *
 * - **主按钮「微信登录」** → `wechat-login`（user-service 真实实现）。
 *   微信 OAuth AppID 尚未申请，[onLogin] 目前收到的是一个稳定 code 串，
 *   服务端按 `wx_<code>` 查/建用户 —— 语义与真 OAuth 完全一致，接入时无需改结构。
 * - **debug 专属「联调登录」** → dev token 端点，用于在真机上冒充指定账号
 *   （比如带历史数据的 user 1）。服务端默认关闭，失败会给出可照做的提示。
 *
 * release 包不渲染任何联调入口，登录路径只有一条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLogin: (code: String) -> Unit = {},
    onDevLogin: ((userId: String) -> Unit)? = null,
    errorMessage: String? = null,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var codeInput by remember { mutableStateOf("dev_wechat_${BuildConfig.DEBUG_USER_ID}") }
    var userIdInput by remember { mutableStateOf(BuildConfig.DEBUG_USER_ID) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("登录听匣") }) },
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 2026-10-03：补 imePadding + verticalScroll
                // imePadding：这是全 App 第一处有输入框的屏，键盘一弹就把
                //   密码框和登录按钮顶出屏幕，而整列是 Arrangement.Center
                //   且不可滚动 —— 用户看得见「登录」但点不到。
                // verticalScroll：系统字号放大到 1.3x 时，logo + 两行副标题 +
                //   48dp 间距 + 两个输入框 + 按钮 + 协议行放不下，
                //   没有滚动条就只能放弃登录。
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("听匣", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("AI 蒸馏你的音频订阅", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(48.dp))

            OutlinedTextField(
                value = codeInput,
                onValueChange = { codeInput = it },
                label = { Text("登录 code（接入微信 OAuth 前为稳定串）") },
                singleLine = true,
                enabled = !isLoading,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Button(
                onClick = { onLogin(codeInput.trim().ifBlank { "anonymous" }) },
                enabled = !isLoading,
            ) {
                Text(if (isLoading) "登录中..." else "微信登录")
            }

            if (onDevLogin != null) {
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = userIdInput,
                    onValueChange = { userIdInput = it },
                    label = { Text("联调 user_id（dev token 端点）") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedButton(
                    onClick = { userIdInput.trim().takeIf { it.isNotBlank() }?.let(onDevLogin) },
                    enabled = !isLoading && userIdInput.isNotBlank(),
                ) {
                    Text("联调登录（需服务端开 STASHBOX_ALLOW_DEV_TOKEN）")
                }
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
                "微信 OAuth AppID 待申请，当前按 code 派生用户",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
