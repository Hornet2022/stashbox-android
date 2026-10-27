package com.tingxia.audio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * 音符进度条的自绘轨道 —— 连续铺满，**恰好止于圆形底座的左缘**。
 *
 * ## 为什么不用 Material3 默认轨道
 *
 * Material3 1.3.1 的默认轨道会在 thumb 槽位两侧各留 **固定 6dp 缺口**
 * （`ThumbTrackGapSize` = 6.dp，取自 `ActiveHandleLeadingSpace`），而且这段缺口的
 * 末端是**直角切断**。缺口 6dp 宽 × 16dp 高（轨道高度），底色 = 页面背景 ⇒
 * 在已播轨道与圆形底座之间呈现一块**边界笔直的浅色矩形**。
 *
 * 用户实拍反馈：「进度条和圆形图形之间有一个很明显的方形块」。
 * 真机像素实测（density 560, 1dp = 3.5px）复现：已播轨道末端在 x=1292 处直角切断，
 * 圆底左缘在 x=1315 ⇒ 缺口正好 21px ≈ 6dp，高 56px = 16dp ⇒ 边界笔直的浅色方块。
 *
 * `SliderDefaults.Track` 在 1.3.1 的公开重载里**没有** `thumbTrackGapSize` 参数
 * （javap 实测：只有 `Track(state, modifier, colors, enabled)`），所以缺口关不掉
 * ⇒ 只能自绘轨道。
 *
 * ## 几何：thumb 槽位位置的换算（必须与 M3 一致，否则接缝错位）
 *
 * 从 M3 1.3.1 字节码（`SliderKt$SliderImpl$2$1`，javap -c）反推出的真实布局算法：
 * ```
 * trackPlaceable = 以 (sliderWidth - thumbWidth) 为宽度测量
 * trackOffsetX   = thumbWidth / 2                 // 轨道画布左缘
 * thumbOffsetX   = round(trackWidth * fraction)   // thumb 槽位左缘
 * ```
 * 换算到**轨道画布本地坐标**（画布左缘 = slider 左缘 + thumbWidth/2）：
 * ```
 * thumb 圆心 = trackWidth * fraction
 * ```
 * ⇒ **thumb 圆心 = fraction × 画布宽度**（不是 `r + f*(W−thumbW)`，那个差 10dp）。
 *
 * 真机两点校验（底部条滑轨，画布 [92, 1348.5]，trackWidth = 1256.5px）：
 * - fraction = 1 → 圆心 x = 1348.5px ✓（圆底 1314..1383）
 * - fraction ≈ 0.034（界面显示 0:04 / 2:08）→ 圆心 x = 92 + 0.034×1256.5 = 135px ✓
 *
 * ## 画法
 *
 * 1. 未播轨道：0..W，整条圆角矩形（圆底覆盖的部分看不见）。
 * 2. 已播轨道：0..(圆心 − r) ⇒ 右端正好落在圆底的**左缘切点**，不骑轨道、不留缝。
 * 3. 末端停止指示点：4dp 圆点，仅在还有未播段时画（与 M3 1.3.1 行为一致）。
 *
 * 轨道高 16dp（= M3 默认轨道高，真机实测 56px @ density 560），与圆底同一条中心线。
 */
@Composable
fun MusicNoteSliderTrack(
    /** 播放进度 0..1（两处 Slider 的 valueRange 都是 0..1）。 */
    fraction: Float,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .testTag(MusicNoteSliderTrackTestTag)
            // ⚠️ 不能用 fillMaxSize()：M3 给轨道槽的约束里高度是「由 thumb 决定的」，
            // fillMaxSize 会把轨道撑到可用最大高度 ⇒ 整条 Slider 被顶歪（实测真机：
            // 滑条跑到屏幕正中间，语义节点铺满全屏）。
            // 固定 16dp 高，M3 会把它垂直居中放在 Slider 里 —— 与圆底同一中心线。
            .height(MusicNoteTrackHeight),
    ) {
        val thumbPx = MusicNoteThumbSize.toPx()
        val trackHeight = MusicNoteTrackHeight.toPx()
        // 圆底半径仅用于几何推导（圆心 = fraction × 画布宽度）
        val centerY = size.height / 2f

        val f = fraction.coerceIn(0f, 1f)
        val thumbCenterX = MusicNoteTrackGeometry.thumbCenterX(f, size.width)

        val corner = CornerRadius(trackHeight / 2f)
        val topLeft = Offset(0f, centerY - trackHeight / 2f)

        // 1. 未播轨道（整条）
        drawRoundRect(
            color = inactiveColor,
            topLeft = topLeft,
            size = Size(size.width, trackHeight),
            cornerRadius = corner,
        )

        // 2. 已播轨道：延伸到圆底中心 ⇒ 轨道与圆形底座**直接相接**，
        //    中间没有任何浅色缝隙；末端取直角（被圆底盖住，看不见圆角回退）。
        if (thumbCenterX > 0f) {
            val path = Path().apply {
                moveTo(trackHeight / 2f, topLeft.y)
                lineTo(thumbCenterX, topLeft.y)
                lineTo(thumbCenterX, topLeft.y + trackHeight)
                lineTo(trackHeight / 2f, topLeft.y + trackHeight)
                quadraticTo(0f, topLeft.y + trackHeight, 0f, centerY)
                quadraticTo(0f, topLeft.y, trackHeight / 2f, topLeft.y)
                close()
            }
            drawPath(path = path, color = activeColor)
        }

        // 3. 末端停止指示点（M3 1.3.1 行为：还有未播段时才画）
        if (f < 1f) {
            val dotRadius = 2.dp.toPx()
            drawCircle(
                color = activeColor,
                radius = dotRadius,
                center = Offset(size.width - dotRadius, centerY),
            )
        }
    }
}


/** 供测试定位自绘轨道画布。 */
const val MusicNoteSliderTrackTestTag = "music-note-slider-track"

/**
 * 自绘轨道与圆底的几何契约（R-MUSIC-TRACK）。
 *
 * - [thumbCenterX]：圆底圆心在**轨道画布本地坐标**里的位置。
 *   M3 1.3.1 的字节码（`SliderKt$SliderImpl$2$1`）：轨道画布左缘 = thumbWidth/2，
 *   thumb 槽位左缘 = round(trackWidth × fraction) ⇒ 圆心 = fraction × 画布宽。
 *   （不是 `r + f×(W−thumbW)` —— 那个公式差 10dp，真机上会让轨道与圆底错开 34px。）
 * - 已播轨道的末端必须画到**圆底中心**（而不是圆底左缘），圆底右半盖住它；
 *   这样轨道与圆底之间不可能出现任何非轨道色的像素带（用户反馈的「白色方块」）。
 */
internal object MusicNoteTrackGeometry {

    /** 圆底圆心（画布本地坐标）。 */
    fun thumbCenterX(fraction: Float, canvasWidthPx: Float): Float =
        fraction.coerceIn(0f, 1f) * canvasWidthPx

    /** 已播轨道末端 = 圆底中心（圆底的右半压住末端）。 */
    fun activeTrackEndX(fraction: Float, canvasWidthPx: Float): Float =
        thumbCenterX(fraction, canvasWidthPx)
}
