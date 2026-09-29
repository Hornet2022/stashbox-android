package com.tingxia.audio.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.audio.PlayerControllerEntryPoint
import com.tingxia.audio.audio.PlaybackState
import dagger.hilt.android.EntryPointAccessors

/**
 * 底部播放器栏（CP4.4 接 ExoPlayer 真实状态；CP8.5 加 onClick 跳全屏；
 * CP-DISTILL-PLAYER 美化：可拖动进度 + 时间 + 播放/暂停真实图标 + 渐变底 + 封面位）。
 *
 * - 直接连 [PlayerController] 单例（通过 Hilt [PlayerControllerEntryPoint]）。
 * - 进度条支持拖动 seek（[Slider] onValueChangeFinished → [PlayerController.seekTo]）。
 *   拖动期间用本地 [seekingFraction] 接管显示，避免 500ms 轮询把滑块拽回去。
 * - 播放/暂停用真实的 [Icons.Filled.PlayArrow] / [Icons.Filled.Pause]
 *   （旧版误用 Add 图标当暂停，是 bug）。
 * - 左侧封面位：播放中显示跳动音波（静态图标占位），不阻塞点击。
 *
 * @param title    音频标题
 * @param audioUrl 音频直链（ready 时传入，作为播放源）
 * @param playerController 可选，便于单测注入；不传则从 Hilt 取单例
 * @param onClick  整栏点击 → 跳全屏播放器
 */
@Composable
fun AudioPlayerBar(
    title: String,
    audioUrl: String? = null,
    articleId: String? = null,
    playerController: PlayerController? = null,
    onClick: () -> Unit = {},
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
    val isBuffering = playbackState == PlaybackState.IDLE && audioUrl != null && position == 0L

    // 拖动进度：seeking 时用本地 fraction，松手才 seekTo，避免与轮询打架
    var seeking by remember { mutableStateOf(false) }
    var seekingFraction by remember { mutableFloatStateOf(0f) }
    val baseFraction = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownFraction = if (seeking) seekingFraction else baseFraction

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val barScale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "barPress",
    )

    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = barScale
                scaleY = barScale
            },
        color = Color.Transparent,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            MaterialTheme.colorScheme.surface,
                        )
                    )
                )
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 12.dp),
        ) {
            // 顶部细进度条（整栏可点 → 全屏）
            Slider(
                value = shownFraction,
                onValueChange = { seeking = true; seekingFraction = it },
                onValueChangeFinished = {
                    if (duration > 0L) {
                        controller.seekTo((seekingFraction * duration).toLong())
                    }
                    seeking = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = primary,
                    activeTrackColor = primary,
                    inactiveTrackColor = muted.copy(alpha = 0.25f),
                ),
                modifier = Modifier.fillMaxWidth().height(20.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 封面 / 音波占位
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = primary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                // 标题 + 时间
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title.ifBlank { "正在播放" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = formatClock(position) + " / " +
                            if (duration > 0L) formatClock(duration) else "--:--",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        fontSize = 11.sp,
                    )
                }

                // 播放 / 暂停（真实图标；点击不冒泡到整栏 onClick）
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(primary)
                        .clickable {
                            if (isPlaying) {
                                controller.pause()
                            } else {
                                val current = controller.currentAudioUrl.value
                                if (playbackState == PlaybackState.PAUSED &&
                                    current.isNotEmpty() && current == audioUrl
                                ) {
                                    controller.resume()
                                } else if (audioUrl != null) {
                                    // articleId 必须传：不传等于这次播放不属于任何文章，
                                    // 完听判定会静默失效（listen-complete 不上报、
                                    // 听感评分卡不弹）。见 PlayerController.currentArticleId。
                                    controller.play(audioUrl, title, articleId = articleId)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

/** 毫秒 → m:ss（超过一小时显示 h:mm:ss）。 */
private fun formatClock(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0L)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format("%d:%02d:%02d", h, m, s)
    } else {
        String.format("%d:%02d", m, s)
    }
}
