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
import com.tingxia.audio.ui.theme.SourceDouyin
import com.tingxia.audio.ui.theme.SourceWechat

/**
 * 来源徽章：根据 source 标识展示「公众号 / 抖音 / 通用」。
 */
@Composable
fun SourceBadge(source: String, modifier: Modifier = Modifier) {
    val (label, color) = when (source.lowercase()) {
        "wechat" -> stringResource(R.string.source_wechat) to SourceWechat
        "douyin" -> stringResource(R.string.source_douyin) to SourceDouyin
        else -> stringResource(R.string.source_generic) to MaterialTheme.colorScheme.outline
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
