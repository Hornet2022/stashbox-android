package com.tingxia.audio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 底部播放器栏（**占位 UI**，CP4.4 才接 ExoPlayer 真实播放）。
 *
 * 当前只渲染：标题 + 播放/暂停按钮（占位切换）+ 进度条（占位）。
 *
 * @param title    音频标题
 * @param audioUrl 音频直链（ready 时传入，用于展示）
 * @param onTogglePlay 占位回调，CP4.4 接 ExoPlayer 时改为实际播放控制
 */
@Composable
fun AudioPlayerBar(
    title: String,
    audioUrl: String? = null,
    onTogglePlay: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var playing by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (audioUrl != null) {
                    Text(
                        text = audioUrl,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 进度条占位（未接 ExoPlayer，固定显示 0% 或占位 30%）
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (playing) 0.3f else 0f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }

            // 播放/暂停按钮（占位，仅切换 UI 状态）
            TextButton(
                onClick = {
                    playing = !playing
                    onTogglePlay(playing)
                },
            ) {
                Text(if (playing) "暂停" else "播放")
            }
        }
    }
}
