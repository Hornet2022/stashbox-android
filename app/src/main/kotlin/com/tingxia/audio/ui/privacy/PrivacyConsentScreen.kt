package com.tingxia.audio.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 首次启动的隐私政策同意闸门（自包含 composable）。
 *
 * ## 怎么接到 MainActivity 里
 *
 * 见交付说明。最小接法是把它放在 [com.tingxia.audio.MainActivity] 的
 * `AuthRoot` 里 `AuthState.LoggedIn` 分支中、`AppNavigation()` 之前：
 *
 * ```kotlin
 * var consentAgreed by remember { mutableStateOf(false) }
 * ...
 * is AuthState.LoggedIn -> {
 *     if (consentAgreed) {
 *         AppNavigation()
 *     } else {
 *         PrivacyConsentGate(onAgreed = { consentAgreed = true })
 *     }
 * }
 * ```
 *
 * ## 为什么放在登录之后
 *
 * `GET/PUT /api/v1/users/me/consent` 需要 JWT（`require_user`），
 * 没有会话就写不了同意记录。政策正文那个端点是公开的（登录前也能读），
 * 但"把同意记下来"这一步必须在有会话时才能做。
 *
 * ## 为什么同意之前不显示任何东西
 *
 * 启动时先查状态再决定要不要弹：否则会出现"同意页闪一下又消失"，
 * 那是这一屏最糟的体验（用户会以为刚才点错了什么）。
 */
@Composable
fun PrivacyConsentGate(
    /** 已经确认同意（或本来就同意过）时回调，由它决定放行到哪一步。 */
    onAgreed: () -> Unit,
    viewModel: PrivacyConsentViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.status) {
        if (state.status == PrivacyConsentViewModel.GateStatus.AGREED) {
            onAgreed()
        }
    }

    when (state.status) {
        PrivacyConsentViewModel.GateStatus.CHECKING -> ConsentLoading()
        PrivacyConsentViewModel.GateStatus.NEEDS_CONSENT -> PrivacyConsentContent(
            state = state,
            onAgree = viewModel::agree,
        )
        PrivacyConsentViewModel.GateStatus.FAILED -> ConsentFailed(
            message = state.error ?: "加载失败，请稍后重试",
            onRetry = viewModel::checkConsent,
        )
        // 已同意：交给 onAgreed 决定下一步，这里不画任何东西。
        PrivacyConsentViewModel.GateStatus.AGREED -> Box(modifier = Modifier.fillMaxSize())
    }
}

/** 正在查状态：不显示同意页（避免闪屏），只显示一个转圈。 */
@Composable
private fun ConsentLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text("正在加载隐私政策…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 加载失败：给一句人话 + 一个能点的重试。 */
@Composable
private fun ConsentFailed(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

/**
 * 同意页本体：政策全文 + 「同意并继续」。
 *
 * 政策全文**必须能完整读完**（可滚动），不能截断 ——
 * "同意"的前提是用户真的读过。
 */
@Composable
private fun PrivacyConsentContent(
    state: PrivacyConsentViewModel.UiState,
    onAgree: () -> Unit,
) {
    val policy = state.policy ?: return
    Scaffold(
        topBar = {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    policy.title.ifBlank { "隐私政策" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    buildString {
                        append("版本 ")
                        append(policy.version.ifBlank { "未知" })
                        if (policy.updatedAt.isNotBlank()) {
                            append("　更新于 ")
                            append(policy.updatedAt)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 政策改版提示：必须让用户知道"你看到的这份已经不是刚才那份了"。
                    state.notice?.let { notice ->
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    state.error?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = onAgree,
                        enabled = state.canAgree,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("同意并继续")
                    }
                    Text(
                        "同意表示你已阅读并接受《${policy.title.ifBlank { "隐私政策" }}》" +
                            "（版本 ${policy.version.ifBlank { "未知" }}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            PolicyMarkdownText(markdown = policy.content)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 把政策正文（Markdown）渲染成可读文本。
 *
 * 处理标题、加粗、列表、引用、分隔线、链接与表格 —— 详见 [PolicyMarkdown]。
 * 用 [androidx.compose.material3.Text] 逐段渲染，不引 Markdown 库。
 */
@Composable
fun PolicyMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { PolicyMarkdown.parse(markdown) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block.kind) {
                PolicyBlockKind.HEADING -> Text(
                    text = block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Bold,
                )

                PolicyBlockKind.PARAGRAPH -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                )

                PolicyBlockKind.BULLET -> Row(
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("· ", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }

                PolicyBlockKind.ORDERED -> Row(
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(
                        text = "${block.order}. ",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }

                PolicyBlockKind.QUOTE -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )

                PolicyBlockKind.RULE -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                PolicyBlockKind.TABLE_ROW -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
