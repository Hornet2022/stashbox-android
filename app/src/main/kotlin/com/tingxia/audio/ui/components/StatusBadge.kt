package com.tingxia.audio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tingxia.audio.R
import com.tingxia.audio.data.model.DistillStatus
import com.tingxia.audio.ui.theme.StatusDistilling
import com.tingxia.audio.ui.theme.StatusFailed
import com.tingxia.audio.ui.theme.StatusListened
import com.tingxia.audio.ui.theme.StatusPending
import com.tingxia.audio.ui.theme.StatusReady

/**
 * 状态 → 文案资源。全 App 只有这一处做状态到中文的映射。
 *
 * 2026-10-03：DistillScreen 之前绕过了这个映射，直接把枚举名小写印出来，
 * 用户看到的是「公众号 · distilling」—— 在一个全中文界面里。
 * 现在两处都走这里。
 */
fun statusLabelRes(status: DistillStatus): Int = when (status) {
    DistillStatus.PENDING -> R.string.status_pending
    DistillStatus.DISTILLING -> R.string.status_distilling
    DistillStatus.READY -> R.string.status_ready
    DistillStatus.FAILED -> R.string.status_failed
    DistillStatus.LISTENED -> R.string.status_listened
}

/**
 * 蒸馏状态徽章：pending / distilling / ready / failed / listened。
 *
 * 2026-10-03：文字色从「状态色本身」改成 onSurface。
 *
 * 原来文字直接用状态色，而底色只有 12% 的同色 tint。算下来 labelSmall
 * 是 11sp，WCAG 对小于 18sp 的正文要求 4.5:1，而五个状态里
 * 待处理 #8C8680 在米白底上只有 2.86:1、已就绪 #6B8E7F 是 2.88:1 ——
 * 全部不达标，深色下有四个不达标。
 *
 * 色相只管底色、文字拿高对比中性色，这个分工本项目在 Color.kt
 * （tag chips）和 NotificationCenterScreen（TagChip）里已经写下并实测过：
 * 六个色相要同时压暗到 4.5:1 的话两两 ΔE 只剩 4.7，全挤进墨色区间，
 * 分类信息反而没了。所以这里沿用同一条结论，不是新发明。
 */
@Composable
fun StatusBadge(status: DistillStatus, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        DistillStatus.PENDING -> stringResource(R.string.status_pending) to StatusPending
        DistillStatus.DISTILLING -> stringResource(R.string.status_distilling) to StatusDistilling
        DistillStatus.READY -> stringResource(R.string.status_ready) to StatusReady
        DistillStatus.FAILED -> stringResource(R.string.status_failed) to StatusFailed
        DistillStatus.LISTENED -> stringResource(R.string.status_listened) to StatusListened
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            // 色相仍然在 —— 在底色上。文字用 onSurface 保证可读。
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
