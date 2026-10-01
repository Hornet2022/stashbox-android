package com.tingxia.audio.ui.tts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingxia.audio.data.model.TtsVoice
import com.tingxia.audio.ui.friendlyError
import kotlinx.coroutines.launch

/**
 * CP-TTS-VOICE: 音色选择底部 sheet。
 *
 * 两处**故意显式**的设计，都是为了不做成假闭环：
 *
 * 1. **显示「实际生效的音色」**而不是「我选中的音色」。用户从没设过偏好时
 *    实际生效的是全局默认音色或全局配置里的参考音频 —— 只显示自己那栏会
 *    出现「没选音色，但听着明显是某个人念的」这种说不通的界面。
 *    `effectiveVoiceName` / `effectiveSource` 就是干这个的。
 *
 * 2. **明确告诉用户「换音色不影响已生成的音频」**。音色是合成期参数，
 *    存量文章要换得重新蒸馏（实测单篇约 23 分钟）。不说清楚，用户会以为
 *    改完立刻所有文章都变声，然后觉得是 bug。
 *
 * ⚠️ `onRequestRegenerate` / `currentArticleId` **不给默认值**。
 * 自测时发现这两个原本有默认（`{}` 和 `null`），而唯一的使用方 SettingsScreen
 * 两样都没传 —— 结果「用新音色重新生成这一篇」那行永远不渲染，
 * 用户要的**回溯重跑入口在 App 里根本点不到**，后端却是通的。
 * 默认值让这种「接错了也编译得过」的情况藏住了。去掉默认值后，
 * 任何新的使用方都必须显式接上，接漏了直接编译失败。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerSheet(
    onDismiss: () -> Unit,
    /** 当前上下文里的文章 id；没有文章上下文时应传 null（此时不显示重生成入口） */
    currentArticleId: String?,
    onRequestRegenerate: (articleId: String) -> Unit,
    viewModel: TtsPreferenceViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var pendingVoiceId by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    /**
     * 打开 sheet 时的**原始**音色 id，作为「本次是否真的换过」的基准。
     *
     * ⚠️ 自测时发现这里原来写的是
     *   `pendingVoiceId != uiState.preference?.voiceId`
     * —— 选完音色后 `selectVoice()` 已经把 preference 更新成同一个值，
     * 两者永远相等，于是「用新音色重新生成这一篇」那行**永远不渲染**。
     * 一个恒为 false 的条件比没有这个功能更难发现：它安静地什么都不做。
     * 正确做法是拿「打开时的快照」比，不是拿「已被自己改过的当前值」比。
     */
    val initialVoiceId = remember { uiState.preference?.voiceId }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Text(
                text = "朗读音色",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = "对新剪藏的文章生效。已生成的音频不会变，" +
                    "想换声音需要重新生成（耗时约 20 分钟）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(16.dp))

            when {
                uiState.isLoading && uiState.voices.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.voices.isEmpty() -> {
                    Text(
                        text = uiState.error
                            ?: "暂无可选音色。管理员还没在后台配置音色库，" +
                            "此时会用系统默认音色朗读。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }

                else -> {
                    // 「跟随默认」作为一个显式选项 —— 它对应后端的 voice_id = null
                    VoiceRow(
                        title = "跟随默认音色",
                        subtitle = uiState.effectiveVoiceName?.let { "当前：$it" }
                            ?: "当前：系统全局配置",
                        selected = uiState.followingDefault,
                        onClick = {
                            scope.launch {
                                pendingVoiceId = null
                                runCatching { viewModel.selectVoice(null) }
                                    .onSuccess { errorText = null }
                                    .onFailure { errorText = friendlyError(it, "切换音色失败") }
                            }
                        },
                    )
                    Spacer(Modifier.size(8.dp))
                    uiState.voices.forEach { voice ->
                        VoiceRow(
                            voice = voice,
                            selected = !uiState.followingDefault &&
                                uiState.preference?.voiceId == voice.id,
                            onClick = {
                                scope.launch {
                                    pendingVoiceId = voice.id
                                    runCatching { viewModel.selectVoice(voice.id) }
                                        .onSuccess { errorText = null }
                                        .onFailure { errorText = friendlyError(it, "切换音色失败") }
                                }
                            },
                        )
                    }
                }
            }

            errorText?.let {
                Spacer(Modifier.size(12.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // 存量文章重生成入口。**只有本次真的换了音色**才提示 ——
            // 否则每次打开都问一遍「要不要重新生成」，是噪音。
            // 基准是打开 sheet 时的快照，不是当前 preference（见 initialVoiceId 的注释）。
            val voiceChanged = pendingVoiceId != null && pendingVoiceId != initialVoiceId
            if (voiceChanged && currentArticleId != null) {
                Spacer(Modifier.size(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            onRequestRegenerate(currentArticleId)
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "用新音色重新生成这一篇",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(
    voice: TtsVoice? = null,
    title: String = voice?.displayName.orEmpty(),
    subtitle: String? = voice?.description,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "当前使用",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
