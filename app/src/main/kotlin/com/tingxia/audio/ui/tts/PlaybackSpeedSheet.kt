package com.tingxia.audio.ui.tts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tingxia.audio.audio.PlayerController
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * CP-TTS-VOICE: 播放速度选择 sheet（设置页与全屏播放器共用）。
 *
 * 抽出来是因为两端要**同一套逻辑** —— 之前全屏播放器里的语速 sheet 只
 * 改本地 state，设置页干脆没有语速入口，两处行为不一致。
 *
 * 语速是**播放端行为**，不重跑蒸馏：实测单篇约 23 分钟，让用户为调速等
 * 一刻钟不可接受。代价是变速后音调会跟着变，这里如实写在 UI 上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSpeedSheet(
    currentSpeed: Float,
    availableSpeeds: List<Float> = emptyList(),
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Text(
                text = "播放速度",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = "立即生效，不影响已生成的音频；变速后音调会随之变化",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            // 档位由服务端下发，不再硬编码 —— 后台改了就改了全端行为
            val speeds = availableSpeeds.ifEmpty { PlayerController.DEFAULT_AVAILABLE_SPEEDS }
            speeds.forEach { value ->
                SpeedRow(
                    label = formatSpeedLabel(value),
                    selected = abs(value - currentSpeed) < 0.01f,
                    onClick = {
                        onSpeedSelected(value)
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SpeedRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            // 选中行用主色 + 加重，让用户看得出「现在是这个速度」
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Speed,
                contentDescription = "当前速度",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 1.0 → "1.0x"、0.75 → "0.75x"、1.25 → "1.25x"。
 *
 * ⚠️ 别用 `if (speed % 1f == 0f) speed.toInt()` —— 那个写法渲染出 "1x"/"2x"，
 * 而 App 另一处和既有五档文案都是 "1.0x"/"2.0x"，同一屏出现两种写法。
 * `Float.toString()` 本身就对：`1.0f.toString() == "1.0"`。
 *
 * 现在只有这一份实现，全屏播放器与设置页都调它（自测时发现原本有两份拷贝，
 * 其中一份注释说「与既有文案保持一致」而代码写的正好相反）。
 */
fun formatSpeedLabel(speed: Float): String = "${speed}x"
