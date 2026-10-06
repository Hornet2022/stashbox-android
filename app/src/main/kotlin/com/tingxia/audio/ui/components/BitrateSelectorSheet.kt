package com.tingxia.audio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.audio.BitrateSelectorViewModel
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.audio.VariantSelectionUseCase
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * §3.1 码率选择弹窗（CP7.4.0 客户端半边）。
 *
 * 显示三档（128 / 96 / 64）可用性 + 文件大小 + 当前选中 + 推荐档（按网络）。
 * 点档后调用 [BitrateSelectorViewModel.pickAndApply]，切换 MediaItem 但保持位置。
 *
 * @param taskId 蒸馏任务 id（§1.3 status 响应里的 task_id）
 */
@Composable
fun BitrateSelectorSheet(
    taskId: String,
    onDismiss: () -> Unit,
    playerController: PlayerController,
    viewModel: BitrateSelectorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(taskId) {
        viewModel.load(taskId)
    }

    LaunchedEffect(uiState.switchError) {
        // 失败 toast 由调用方观察，这里仅 reset
        if (uiState.switchError != null) {
            // 让用户看到错误，3 秒后清掉（轻量处理，不阻塞 dismiss）
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "选择音质",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "当前网络：${scenarioLabel(uiState.scenario)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (uiState.isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                } else if (uiState.error != null) {
                    Text(
                        uiState.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    uiState.variants.forEach { v ->
                        BitrateRow(
                            bitrate = v.bitrate,
                            available = v.available,
                            fileSizeBytes = v.fileSizeBytes,
                            isMain = v.isMain,
                            isCurrent = uiState.currentBitrate == v.bitrate,
                            isPreferred = uiState.preferredBitrate == v.bitrate,
                            enabled = v.available && !uiState.isSwitching,
                            onClick = { viewModel.pickAndApply(v.bitrate) },
                        )
                    }

                    val switchErrorText = uiState.switchError
                    if (switchErrorText != null) {
                        Text(
                            switchErrorText,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.clearSwitchError()
                onDismiss()
            }) { Text("关闭") }
        },
    )
}

@Composable
private fun BitrateRow(
    bitrate: Int,
    available: Boolean,
    fileSizeBytes: Long?,
    isMain: Boolean,
    isCurrent: Boolean,
    isPreferred: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = isCurrent,
                onClick = { if (enabled) onClick() },
                role = Role.RadioButton,
                enabled = enabled,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isCurrent, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${bitrate}k",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isPreferred) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (available) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outline,
                )
                if (isMain) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "主档",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (isPreferred && available) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "推荐",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            val sizeLabel = fileSizeBytes?.let { "${"%.1f".format(it / 1024.0 / 1024.0)} MB" } ?: ""
            val availLabel = if (!available) "（不可用）" else ""
            Text(
                "$sizeLabel $availLabel".trim(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun scenarioLabel(scenario: VariantSelectionUseCase.Scenario): String = when (scenario) {
    VariantSelectionUseCase.Scenario.WIFI_ONLINE -> "Wi-Fi"
    VariantSelectionUseCase.Scenario.CELLULAR_ONLINE -> "蜂窝网络"
    VariantSelectionUseCase.Scenario.OFFLINE_PRELOAD -> "离线"
    VariantSelectionUseCase.Scenario.DATA_SAVER -> "省流量模式"
    VariantSelectionUseCase.Scenario.ETHERNET -> "Wi-Fi"
}