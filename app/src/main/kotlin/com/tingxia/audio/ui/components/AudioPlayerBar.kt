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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.ui.theme.Cream
import com.tingxia.audio.ui.theme.MusicNoteKnob
import com.tingxia.audio.audio.PlayerControllerEntryPoint
import com.tingxia.audio.audio.PlaybackState
import dagger.hilt.android.EntryPointAccessors
import androidx.compose.foundation.layout.navigationBarsPadding

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
@OptIn(ExperimentalMaterial3Api::class) // Slider.thumb 参数是 experimental API（material3 1.3.1）
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

    val playbackState by controller.state.collectAsStateWithLifecycle()
    val position by controller.position.collectAsStateWithLifecycle()
    val duration by controller.duration.collectAsStateWithLifecycle()

    // 这个栏有活的进度条，必须在可见期间拿到 500ms 刷新率；
    // 不可见（滚走 / App 退后台）时把轮询降频，声音照放不误。
    ObservePlayerPositionUpdates(controller)

    // RECOVERING 也算「在播」（2026-10-26）：它只是暂时断了流，
    // 按钮该显示暂停、点了也该能暂停 —— 而不是跳去「重新起播」，
    // 那会把已经听过的部分从头再来一遍。
    val isPlaying = playbackState == PlaybackState.PLAYING ||
        playbackState == PlaybackState.RECOVERING
    val isBuffering = playbackState == PlaybackState.IDLE && audioUrl != null && position == 0L

    // 拖动进度：seeking 时用本地 fraction，松手才 seekTo，避免与轮询打架
    var seeking by remember { mutableStateOf(false) }
    var seekingFraction by remember { mutableFloatStateOf(0f) }
    val baseFraction = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownFraction = if (seeking) seekingFraction else baseFraction

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 安全网：`seeking` 只在 onValueChangeFinished 里复位，而那个回调**不保证**
    // 一定来 —— 手势被系统打断（父容器拦截、返回手势、多指操作、系统对话框）
    // 时它可能根本不触发。一旦漏掉，`seeking` 就永久停在 true，
    // `shownFraction` 会一直读本地 `seekingFraction` 而不是播放位置：
    // 进度条**冻在你松手那一刻的值**，直到下次碰它才跳一下。
    //
    // 这里不依赖任何回调的到达，只看"手指还在不在控件上"：
    // isPressed 变 false 就是手势一定结束了（无论正常松手还是被打断）。
    // 正常路径下两者一致，重复复位是幂等的。
    LaunchedEffect(isPressed) {
        if (!isPressed) seeking = false
    }
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
                // 2026-10-03 真机发现：这一条被系统导航栏压住。
                // 原来只有一个固定的 bottom = 12.dp，于是整条播放条画在
                // 屏幕最底部，而三键导航的返回/主页/多任务正好落在那一块 ——
                // 实测截图里标题和暂停键被系统键叠住，两个都点不准。
                // navigationBarsPadding 让内容抬到导航栏之上；在手势导航机型上
                // 这个 inset 约为 0，所以不会凭空多出一块空白。
                .navigationBarsPadding()
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
                // 音符指示器（R-MUSIC-THUMB 2026-10-27）：替换默认圆形 thumb。
                // 热区不受影响 —— Material3 的命中区域由 Slider 内部处理，与 thumb 视觉尺寸无关。
                // 圆底用墨黑、字形用米白：圆底**不得**等于轨道色（primary）——
                // 同色会把圆底和轨道糊成一块（用户 2026-10-27 反馈）。
                thumb = { MusicNoteSliderThumb(color = MusicNoteKnob, glyphColor = Cream) },
                // 自绘轨道（R-MUSIC-THUMB）：Material3 默认轨道在 thumb 两侧固定留
                // 6dp 缺口且末端直角切断 ⇒ 轨道与圆底之间出现一块「白色方块」。
                // 这里让轨道连续铺满、恰好止于圆底左缘。
                track = {
                    MusicNoteSliderTrack(
                        fraction = shownFraction,
                        activeColor = primary,
                        inactiveColor = muted.copy(alpha = 0.25f),
                    )
                },
                // ⚠️ 坑：原来这里是 Modifier.fillMaxWidth().height(20.dp)，
                // 那个 height 把 material3 内部给 Slider 预留的 48dp
                // 最小交互尺寸直接压掉了 —— 滑块看着细，但**手指能按到的热区
                // 只有 20dp 高**，拖动极难对准（R6.5）。
                // 想让进度条看起来细一点，只需要调 colors / 轨道粗细，
                // 不要动控件本身的高度。
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = interactionSource,
                        // R7.5：涟漪是 Material 的标准反馈。
                        // 原来这里是 indication = null —— 涟漪被关掉了，
                        // 用户按下去一点反应都没有。
                        // 按压缩放动画（上面的 barScale）保留 —— 那是这个栏原本的手感。
                        //
                        // ⚠️ 必须显式给 indication：带 interactionSource 的
                        // clickable 重载没有「默认涟漪」这个参数位，
                        // 不传就编译不过（而带默认 interactionSource 的重载
                        // 又拿不到我们这个 interactionSource，按压动画会失联）。
                        indication = ripple(),
                        onClick = onClick,
                    )
                    // R6.2：整栏可点 → 跳全屏播放器，但语义里只有标题文字，
                    // 读屏用户不知道点了会去哪里。
                    // contentDescription 说清**去向**（"打开全屏播放器"）。
                    .semantics { contentDescription = "打开全屏播放器" },
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
                        // R4.3：只用 typography 角色，不要在角色之上再写死字号。
                        // 原来是 labelSmall + fontSize = 11.sp —— 写死的 11sp
                        // 把字重/行高/字距都绕过了 typography，也不再跟随
                        // 用户的大字号设置（labelSmall 本来就是 11sp，
                        // 覆盖它没有任何收益，只有风险）。
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
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
                                } else {
                                    // ⚠️ 原来这里什么都不做 —— 音频地址还没取到时，
                                    // 播放键就是个死按钮（点了像 App 坏了）。给个提示。
                                    Toast.makeText(
                                        context,
                                        "音频地址还没准备好，请稍后重试",
                                        Toast.LENGTH_SHORT,
                                    ).show()
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
