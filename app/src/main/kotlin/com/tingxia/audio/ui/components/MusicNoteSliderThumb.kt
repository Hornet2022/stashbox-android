package com.tingxia.audio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * 播放进度条的「音符」指示器（thumb）—— 实心圆底 + 音符。
 *
 * 需求（2026-10-27）：两处播放进度条（底部常驻条 + 全屏播放器）的进度指示器
 * 换成音符图标，呼应「听匣」的音乐产品属性。
 *
 * ## 为什么圆底是功能必需，不是装饰
 *
 * Material3 1.3.1 的 thumb 两侧各留 **6dp 固定空隙**（`ThumbTrackGapSize`，
 * 取自 `ActiveHandleLeadingSpace` = 6.dp，与 thumb 尺寸无关），且这个空隙是
 * **从 thumb 槽位边缘**算起的，不是从绘制出来的图形算起。
 * ⇒ 槽位宽 20dp 时轨道端点距中心 10 + 6 = 16dp；MusicNote 在 20dp 框里只画约
 * 10dp 宽 ⇒ 音符边缘距中心约 5dp，**两边仍各留约 11dp 净空**。
 *
 * 所以音符**不会**骑在轨道上，它浮在两段轨道之间的页面背景上。
 * 正因如此，音符颜色只需与**页面背景**形成对比（底部条 surface 浅色 ⇒ 主色；
 * 全屏播放器深色 ⇒ Cream），而圆底负责让音符在两段轨道之间仍然"立"得住，
 * 同时保证深/浅两种配色下音符字形都有足够对比（实测 6.99:1）。
 *
 * ## 为什么用 Rounded 字形而不是 Filled
 *
 * `Icons.Filled.MusicNote` 的符尾是**硬边直角矩形**（8 倍真机放大图实测：
 * 音符右上角一块明显的方角色块）。用户反馈「进度条上有一个很明显的方形块
 * 包着音符图标」—— 真机全屏扫描确认：圆底之外**唯一的硬边方块就是这枚符尾**，
 * 圆形底本身没有方角，轨道端点也是圆角矩形。
 * ⇒ 改用 `Icons.Rounded.MusicNote`：符尾与符头全部圆角，画面上不再出现
 * 任何硬边直角色块，同时保留 Filled 版本的辨识度与视觉重量。
 *
 * ## 尺寸依据（真机像素实测，density 560，1dp = 3.5px）
 *
 * 用户反馈原版指示器「太大、太突兀」。真机量到 Material3 1.3.1 的默认 thumb
 * 是 **4dp × 44dp 的竖条**，骑在约 12dp 高的细轨道上、上下各突出约 16dp ——
 * 用户说的那根「|」就是它。
 *
 * 换成 20dp 的圆：竖向溢出降到 4dp/侧（轨道的 1/3），横向是一个圆形手柄而非
 * 一根游离竖线；音符图标 16dp（Material 的 MusicNote 路径只画满 24dp 视口的一半
 * 左右，实测可见字形 8 × 12dp），在圆里占比清晰且不拥挤。
 *
 * 可拖拽热区不受影响：Material3 Slider 的命中区域由内部的
 * sliderTapModifier / sliderPressDragModifier 处理（覆盖整个 Slider 宽度 + 48dp 高），
 * 与 thumb 的视觉尺寸无关。
 *
 * ## 「白色方块」= M3 固定 6dp thumb 缺口（由自绘轨道消除）
 *
 * 用户实拍反馈：进度条与圆形之间有一块明显的**浅色方块**把两者隔开。
 * 真机像素实测（density 560, 1dp = 3.5px）定位到成因：Material3 1.3.1 的轨道
 * 在 thumb 槽位两侧固定留 6dp 缺口，且末端是直角切断 ⇒ 缺口呈 6dp × 16dp 的
 * **边界笔直的浅色矩形**。`SliderDefaults.Track` 未暴露该参数 ⇒ 改用自绘轨道
 * `MusicNoteSliderTrack`（轨道连续铺满、恰好止于圆底左缘），缺口从根上不存在。
 *
 * ## 圆底配色契约：圆底 ≠ 轨道色（R-MUSIC-KNOB 2026-10-27）
 *
 * 用户反馈：「圆底的着色和进度轨道的颜色是一样的，不好看」。
 * 之前圆底直接取了所在 Slider 的 activeTrackColor（底部条 = primary 暖赭、
 * 全屏 = Cream）⇒ 圆底和轨道糊成同一块，指示器的圆形读不出来。
 *
 * 现在的契约：**圆底用墨黑 Ink，音符字形用米白 Cream**。
 * 取值依据（真机像素实测衬底色）：
 * - 底部条衬底 = #F5F2EB（米白）⇒ 墨黑圆底压它 15.4:1；
 * - 全屏播放器滑条处衬底 = #62503E（深褐）⇒ 墨黑圆底压它 2.26:1；
 * - 对轨道色：墨黑压暖赭 #795031 = 2.47:1，压米白 #F5F2EB = 15.4:1
 *   ⇒ 两处的圆底都立刻从轨道里"跳"出来；
 * - 音符字形：米白压墨黑 = 15.4:1 ✓。
 *
 * 用法：`Slider(..., track = { state -> MusicNoteSliderTrack(state, ...) },
 * thumb = { MusicNoteSliderThumb(color = MusicNoteKnob, glyphColor = Cream) })`
 *
 * @param color 圆底颜色；**不得**取所在 Slider 的 activeTrackColor。
 * @param glyphColor 音符字形颜色；默认按圆底亮度自动取对比色。
 */
@Composable
fun MusicNoteSliderThumb(color: Color, glyphColor: Color? = null) {
    val onColor = glyphColor
        ?: if (color.luminance() > 0.5f) Color(0xFF1A1A1A) else Color.White
    Box(
        modifier = Modifier
            .size(MusicNoteThumbSize)
            .background(color = color, shape = CircleShape)
            .testTag(MusicNoteSliderThumbTestTag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null, // 指示器为装饰，拖动语义由 Slider 本身提供
            tint = onColor,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 供测试/无障碍定位音符指示器（R-MUSIC-THUMB）。 */
const val MusicNoteSliderThumbTestTag = "music-note-slider-thumb"

/** 音符指示器（圆底）直径。 */
val MusicNoteThumbSize = 20.dp

/** Material3 1.3.1 默认轨道高度（真机像素实测 16dp = 56px @ density 560）。 */
val MusicNoteTrackHeight = 16.dp
