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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tingxia.audio.R
import com.tingxia.audio.data.model.DistillStatus

/**
 * 蒸馏状态徽章：pending / distilling / ready / failed / listened。
 */
@Composable
fun StatusBadge(status: DistillStatus, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        DistillStatus.PENDING -> stringResource(R.string.status_pending) to Color(0xFF9E9E9E)
        DistillStatus.DISTILLING -> stringResource(R.string.status_distilling) to Color(0xFF2196F3)
        DistillStatus.READY -> stringResource(R.string.status_ready) to Color(0xFF4CAF50)
        DistillStatus.FAILED -> stringResource(R.string.status_failed) to Color(0xFFF44336)
        DistillStatus.LISTENED -> stringResource(R.string.status_listened) to Color(0xFF9C27B0)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
