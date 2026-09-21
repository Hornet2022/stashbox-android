package com.tingxia.audio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * CP11.0.4 P1.2 + P3.1 配额提示条:
 * - Healthy: 不显示
 * - Low (<= 10%): 橙色条 + "本月配额快用完,剩余 X 篇"
 * - Exhausted: 红色条 + "本月配额已用完,升级会员" (但此时通常已被 Paywall 拦)
 * - Unknown: 不显示(避免误伤)
 *
 * 接入:CaptureScreen / AddScreen 顶部。
 */
@Composable
fun QuotaBanner(
    used: Int?,
    total: Int?,
    remaining: Int?,
    modifier: Modifier = Modifier,
) {
    if (used == null || total == null || remaining == null || total <= 0) return

    val pct = (remaining.toFloat() / total.toFloat())
    when {
        remaining <= 0 -> {
            BannerBlock(
                color = MaterialTheme.colorScheme.errorContainer,
                iconTint = MaterialTheme.colorScheme.error,
                icon = Icons.Filled.Warning,
                title = "本月配额已用完",
                subtitle = "升级会员,继续剪藏 · 蒸馏",
                modifier = modifier,
            )
        }
        pct <= 0.10f -> {
            BannerBlock(
                color = Color(0xFFFFE0B2),         // 橙
                iconTint = Color(0xFFE65100),      // 深橙
                icon = Icons.Filled.Warning,
                title = "本月配额快用完",
                subtitle = "剩余 $remaining / $total 篇(≤10%)",
                modifier = modifier,
            )
        }
        else -> Unit    // Healthy 不显示
    }
}

@Composable
private fun BannerBlock(
    color: Color,
    iconTint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}