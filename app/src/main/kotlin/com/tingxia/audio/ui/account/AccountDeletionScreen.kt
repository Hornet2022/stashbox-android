package com.tingxia.audio.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingxia.audio.data.model.ACCOUNT_DELETE_CONFIRM_TEXT
import com.tingxia.audio.data.model.AccountDeleteResult

/**
 * 注销账号界面（2026-10-21）。
 *
 * ## 为什么这一屏要这么"重"
 *
 * 注销是**不可逆**的终局操作：服务端会把账号匿名化、文章软删、token 全部吊销，
 * 没有任何撤销入口。所以这一屏的三个设计都是服务同一个目的 ——
 * **让用户在按下按钮之前，清楚地知道自己要放弃什么**：
 *
 * 1. 后果清单（不是一句"此操作不可撤销"了事）；
 * 2. 打字确认（逐字输入 `注销听匣`），让"点一下"变成"想一下再点"；
 * 3. 按钮在短语匹配之前**保持禁用**，而不是点了才弹错误。
 *
 * ## 接线
 *
 * 这一屏既可以注册成 NavHost 路由（推荐，见交付说明），
 * 也可以由设置页内联展示（见 [com.tingxia.audio.ui.settings.SettingsScreen]）。
 * 两种接法用的是同一个 composable，行为完全一致。
 */

/** 确认输入框的 testTag（单测要靠它定位输入框）。 */
const val CONFIRM_FIELD_TAG = "account_delete_confirm"
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDeletionScreen(
    onBack: () -> Unit,
    /** 注销完成、用户看完结果并点了「完成」后回调 —— 由它把登录态翻回未登录。 */
    onAccountDeleted: () -> Unit = {},
    viewModel: AccountDeletionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 取成局部 val：supportingText 的 lambda 里再做一次智能转换不可靠，
    // 而且下面还要复用它。
    val errorText = state.error

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("注销账号") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 不可逆的醒目提示 ──
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "注销后无法恢复",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "这是终局操作：账号会被注销，数据不可取回，也无法再登录回来。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // ── 后果清单 ──
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "注销会发生什么",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    DeletionConsequence(
                        "账号信息被立即匿名化",
                        "昵称、OpenID、手机号、邮箱、头像等个人身份信息被清空，不可恢复。",
                    )
                    DeletionConsequence(
                        "全部文章被软删除",
                        "你剪藏的文章不再可见，音频文件将在 30 天内由运营批量清理。",
                    )
                    DeletionConsequence(
                        "个人数据被删除",
                        "播放进度、下载记录、反馈与评分等与账号关联的数据一并删除。",
                    )
                    DeletionConsequence(
                        "本机数据被清空",
                        "本机的登录状态、下载的音频、播放进度与后台任务全部清除。",
                    )
                    DeletionConsequence(
                        "推送被停用",
                        "所有设备上的推送通知停止投递。",
                    )
                }
            }

            HorizontalDivider()

            // ── 打字确认 ──
            Text(
                "输入「$ACCOUNT_DELETE_CONFIRM_TEXT」以确认",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            OutlinedTextField(
                value = state.confirmText,
                onValueChange = viewModel::onConfirmTextChange,
                label = { Text("确认短语") },
                placeholder = { Text(ACCOUNT_DELETE_CONFIRM_TEXT) },
                singleLine = true,
                enabled = !state.isSubmitting && state.result == null,
                isError = state.error != null,
                supportingText = if (errorText != null) {
                    { Text(errorText) }
                } else {
                    null
                },
                // testTag：单测要往这个输入框里打字，靠 label 定位不稳
                // （label 和 placeholder 的文字会互相干扰）。
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CONFIRM_FIELD_TAG),
            )

            Button(
                onClick = viewModel::submit,
                // 确认短语不匹配时按钮保持禁用 —— 不是"点了才告诉你不行"。
                enabled = state.canSubmit,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onError,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("确认注销账号")
            }

            Text(
                "注销前请确认你需要的内容已处理完毕。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
        }
    }

    // ── 结果 ──
    // 结果用弹窗而不是直接替换页面：用户需要**读到**"注销了、软删了几篇"，
    // 而不是看着页面一闪就回到登录页。
    state.result?.let { result ->
        AccountDeletionResultDialog(
            result = result,
            onConfirm = onAccountDeleted,
        )
    }
}

/** 后果清单的一行：粗体结论 + 一句解释。 */
@Composable
private fun DeletionConsequence(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "· $title",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * 注销结果弹窗。
 *
 * 两种结果分开说：
 * - 正常注销：告诉用户**软删了几篇文章**（这是他最关心的"我丢了什么"）；
 * - 之前已注销（`already_deleted`）：这次没有新的处理发生 ——
 *   但**照样要登出**（服务端幂等返回，客户端不能因为"没变化"就留在已登录态）。
 */
@Composable
private fun AccountDeletionResultDialog(
    result: AccountDeleteResult,
    onConfirm: () -> Unit,
) {
    val message = if (result.alreadyDeleted) {
        "该账号此前已注销，本次没有新的处理。你的登录状态已清空，本机数据已清除。"
    } else {
        "账号已注销。${result.articlesSoftDeleted} 篇文章已软删除，" +
            "个人信息已匿名化，本机数据已清除。"
    }
    AlertDialog(
        onDismissRequest = { /* 不可点外面关掉：结果必须被看到 */ },
        title = { Text("账号已注销") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("完成") }
        },
    )
}
