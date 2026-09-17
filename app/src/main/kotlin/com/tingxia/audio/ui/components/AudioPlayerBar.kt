package com.tingxia.audio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.audio.PlayerControllerEntryPoint
import com.tingxia.audio.audio.PlaybackState
import dagger.hilt.android.EntryPointAccessors

/**
 * 底部播放器栏（CP4.4 接 ExoPlayer 真实状态）。
 *
 * - 直接连 [PlayerController] 单例（通过 Hilt [PlayerControllerEntryPoint]），
 *   显示真实播放状态 [PlaybackState] 与进度 [PlayerController.position]/[PlayerController.duration]
 * - 播放/暂停按钮：调用 [PlayerController.play] / [PlayerController.pause]
 * - 进度条：真实进度（0-1），由 PlayerController 每 500ms 轮询 ExoPlayer 更新
 *
 * 测试时可传入 [playerController] 避免依赖 Hilt 组件装配。
 *
 * @param title        音频标题
 * @param audioUrl     音频直链（ready 时传入，作为播放源 + 展示）
 * @param playerController 可选，便于单测注入；不传则从 Hilt 取单例
 */
@Composable
fun AudioPlayerBar(
    title: String,
    audioUrl: String? = null,
    playerController: PlayerController? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controller = playerController ?: remember {
        EntryPointAccessors
            .fromApplication(context.applicationContext, PlayerControllerEntryPoint::class.java)
            .playerController()
    }

    val playbackState by controller.state.collectAsState()
    val position by controller.position.collectAsState()
    val duration by controller.duration.collectAsState()

    val isPlaying = playbackState == PlaybackState.PLAYING
    val progress = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

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

            // 真实进度条（由 PlayerController 每 500ms 轮询 ExoPlayer 更新）
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.width(80.dp),
            )

            TextButton(
                onClick = {
                    if (isPlaying) {
                        controller.pause()
                    } else {
                        audioUrl?.let { controller.play(it) }
                    }
                },
            ) {
                Text(if (isPlaying) "暂停" else "播放")
            }
        }
    }
}
